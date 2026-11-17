use pg_core::catalog::Catalog;
use pg_core::model::*;
use pg_core::phone::*;

fn media(id: &str, name: &str, taken: i64) -> PhoneMedia {
    PhoneMedia {
        id: id.into(),
        name: name.into(),
        mime: "image/jpeg".into(),
        folder: "camera".into(),
        folder_name: "Camera".into(),
        taken,
        modified: taken,
        size_bytes: 1000,
        width: 4,
        height: 3,
        favorite: false,
    }
}

fn account() -> AccountId {
    AccountId::new("account").unwrap()
}

fn remote(node: &str, name: &str, time: i64) -> Asset {
    Asset {
        key: AssetKey {
            account: account(),
            volume: VolumeId::new("photos-volume").unwrap(),
            node: NodeId::new(node).unwrap(),
        },
        name: name.into(),
        kind: MediaKind::Image,
        mime: "image/jpeg".into(),
        revision: None,
        size_bytes: Some(1000),
        sort_time: time,
    }
}

async fn setup() -> (Catalog, SourceId) {
    let catalog = Catalog::in_memory().await.unwrap();
    catalog
        .register_source(phone_source().unwrap())
        .await
        .unwrap();
    let photos = SourceSpec {
        id: SourceId::new("photos-1").unwrap(),
        account: account(),
        volume: VolumeId::new("photos-volume").unwrap(),
        root: NodeId::new("root").unwrap(),
        label: "Proton Photos".into(),
    };
    catalog.register_source(photos.clone()).await.unwrap();
    (catalog, photos.id)
}

async fn names(catalog: &Catalog, photos: &SourceId, account: Option<AccountId>) -> Vec<String> {
    let page = catalog
        .timeline_window(
            Some(photos.clone()),
            account,
            None,
            false,
            PageSize::new(50).unwrap(),
        )
        .await
        .unwrap();
    page.items
        .into_iter()
        .map(|a| {
            format!(
                "{}:{}",
                if is_phone(&a.key) { "phone" } else { "proton" },
                a.name
            )
        })
        .collect()
}

#[tokio::test]
async fn the_timeline_shows_each_photo_once_whether_on_the_phone_in_proton_or_both() {
    let (catalog, photos) = setup().await;
    catalog
        .add_to_source(photos.clone(), remote("r1", "IMG_1.jpg", 100))
        .await
        .unwrap();
    catalog
        .phone_upsert(
            1,
            vec![media("1", "IMG_1.jpg", 100), media("2", "IMG_2.jpg", 200)],
        )
        .await
        .unwrap();

    // Signed out: everything on the phone is shown.
    assert_eq!(
        names(&catalog, &photos, None).await,
        ["phone:IMG_2.jpg", "phone:IMG_1.jpg", "proton:IMG_1.jpg"]
    );
    // Signed in, before the check: a same-named Proton photo hides the phone copy.
    let signed_in = Some(account());
    assert_eq!(
        names(&catalog, &photos, signed_in.clone()).await,
        ["phone:IMG_2.jpg", "proton:IMG_1.jpg"]
    );
    assert_eq!(
        catalog
            .timeline_count(Some(photos.clone()), signed_in.clone())
            .await
            .unwrap(),
        2
    );
    assert_eq!(
        catalog.phone_unchecked(account(), 10).await.unwrap().len(),
        2
    );

    // Same name, different contents: both are shown.
    catalog
        .phone_mark(
            account(),
            vec![
                ("1".into(), BackupState::Missing),
                ("2".into(), BackupState::Missing),
            ],
        )
        .await
        .unwrap();
    assert_eq!(names(&catalog, &photos, signed_in.clone()).await.len(), 3);

    // Matched: one tile, and the Proton photo knows its phone copy.
    let copy = BackupState::BackedUp {
        volume: VolumeId::new("photos-volume").unwrap(),
        node: NodeId::new("r1").unwrap(),
    };
    catalog
        .phone_mark(account(), vec![("1".into(), copy)])
        .await
        .unwrap();
    assert_eq!(
        names(&catalog, &photos, signed_in.clone()).await,
        ["phone:IMG_2.jpg", "proton:IMG_1.jpg"]
    );
    let links = catalog
        .phone_links(
            signed_in.clone(),
            vec![
                remote("r1", "", 0).key,
                phone_key("2").unwrap(),
                phone_key("1").unwrap(),
            ],
        )
        .await
        .unwrap();
    assert_eq!(
        links[0],
        PhoneLink {
            phone: Some("1".into()),
            backed_up: true
        }
    );
    assert_eq!(
        links[1],
        PhoneLink {
            phone: Some("2".into()),
            backed_up: false
        }
    );
    assert!(links[2].backed_up);

    // Only unsaved media in chosen folders is queued.
    assert!(
        catalog
            .backup_queue(account(), 10)
            .await
            .unwrap()
            .is_empty()
    );
    catalog
        .set_backup_folder("camera".into(), true)
        .await
        .unwrap();
    assert_eq!(
        catalog.backup_queue(account(), 10).await.unwrap(),
        [("2".to_string(), false)]
    );
    let folders = catalog.phone_folders(signed_in).await.unwrap();
    assert_eq!(
        (
            folders[0].count,
            folders[0].not_backed_up,
            folders[0].backup
        ),
        (2, 1, true)
    );
}

#[tokio::test]
async fn a_changed_file_is_checked_again_and_a_deleted_one_disappears() {
    let (catalog, photos) = setup().await;
    catalog
        .phone_upsert(1, vec![media("1", "a.jpg", 10), media("2", "b.jpg", 20)])
        .await
        .unwrap();
    catalog
        .phone_set_hashes(vec![("1".into(), "a".repeat(40))])
        .await
        .unwrap();
    catalog
        .phone_mark(
            account(),
            vec![
                ("1".into(), BackupState::Maybe),
                ("2".into(), BackupState::Missing),
            ],
        )
        .await
        .unwrap();
    assert_eq!(
        catalog.phone_hashed(account(), 10).await.unwrap(),
        [("1".into(), "a.jpg".into(), "a".repeat(40))]
    );
    assert!(
        catalog
            .phone_set_hashes(vec![("1".into(), "A".repeat(40))])
            .await
            .is_err()
    );

    let mut edited = media("1", "a.jpg", 10);
    edited.modified = 99;
    catalog.phone_upsert(2, vec![edited]).await.unwrap();
    assert_eq!(catalog.phone_finish(2).await.unwrap(), 1);
    let entry = catalog
        .phone_entry("1".into(), Some(account()))
        .await
        .unwrap()
        .unwrap();
    assert_eq!((entry.sha1, entry.state), (None, BackupState::Unchecked));
    assert!(
        catalog
            .phone_entry("2".into(), Some(account()))
            .await
            .unwrap()
            .is_none()
    );
    assert_eq!(names(&catalog, &photos, None).await, ["phone:a.jpg"]);
}

#[tokio::test]
async fn the_merged_timeline_pages_both_ways_without_gaps() {
    let (catalog, photos) = setup().await;
    for i in 0..40 {
        catalog
            .add_to_source(
                photos.clone(),
                remote(&format!("r{i}"), &format!("p{i}.jpg"), i * 2),
            )
            .await
            .unwrap();
    }
    catalog
        .phone_upsert(
            1,
            (0..40)
                .map(|i| media(&i.to_string(), &format!("m{i}.jpg"), i * 2 + 1))
                .collect(),
        )
        .await
        .unwrap();
    let size = PageSize::new(25).unwrap();
    let mut seen = Vec::new();
    let mut cursor = None;
    let mut pages = Vec::new();
    loop {
        let page = catalog
            .timeline_window(
                Some(photos.clone()),
                Some(account()),
                cursor.clone(),
                false,
                size,
            )
            .await
            .unwrap();
        pages.push(page.items.clone());
        seen.extend(page.items.iter().map(|a| a.sort_time));
        match page.next {
            Some(next) => cursor = Some(next),
            None => break,
        }
    }
    assert_eq!(seen, (0..80).rev().collect::<Vec<_>>());
    let last = catalog
        .timeline_window(Some(photos.clone()), Some(account()), cursor, false, size)
        .await;
    assert!(last.is_ok());
    let first = catalog
        .timeline_window(Some(photos.clone()), Some(account()), None, false, size)
        .await
        .unwrap();
    let second = catalog
        .timeline_window(
            Some(photos.clone()),
            Some(account()),
            first.next,
            false,
            size,
        )
        .await
        .unwrap();
    assert_eq!(second.items, pages[1]);
    let back = catalog
        .timeline_window(
            Some(photos.clone()),
            Some(account()),
            second.previous,
            true,
            size,
        )
        .await
        .unwrap();
    assert_eq!(back.items, pages[0]);
    let other = catalog
        .window(Some(photos.clone()), None, false, size)
        .await
        .unwrap()
        .next
        .unwrap();
    assert!(
        catalog
            .timeline_window(Some(photos), None, Some(other), false, size)
            .await
            .is_err()
    );
}
