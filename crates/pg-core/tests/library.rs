use pg_core::{catalog::Catalog, library::SourceKind, model::*};

fn folder(id: &str) -> SourceSpec {
    SourceSpec {
        id: SourceId::new(id).unwrap(),
        account: AccountId::new("account").unwrap(),
        volume: VolumeId::new("volume").unwrap(),
        root: NodeId::new(id).unwrap(),
        label: id.into(),
    }
}

#[tokio::test]
async fn nested_and_overlapping_galleries_keep_independent_parent_edges() {
    let c = Catalog::in_memory().await.unwrap();
    let (root, child, leaf, other) = (
        folder("root"),
        folder("child"),
        folder("leaf"),
        folder("other"),
    );
    c.register_album(root.id.clone(), root.clone(), None)
        .await
        .unwrap();
    c.register_album(root.id.clone(), child.clone(), Some(root.id.clone()))
        .await
        .unwrap();
    c.register_album(root.id.clone(), leaf.clone(), Some(child.id.clone()))
        .await
        .unwrap();
    c.register_album(other.id.clone(), other.clone(), None)
        .await
        .unwrap();
    c.register_album(other.id.clone(), child.clone(), Some(other.id.clone()))
        .await
        .unwrap();
    let roots = c.albums(None, None).await.unwrap();
    assert_eq!(roots.len(), 2);
    let children = c
        .albums(Some(root.id.clone()), Some(root.id.clone()))
        .await
        .unwrap();
    assert_eq!(children[0].source.id, child.id);
    assert_eq!(children[0].child_albums, 1);
    let nested = c
        .albums(Some(root.id.clone()), Some(child.id.clone()))
        .await
        .unwrap();
    assert_eq!(nested[0].source.id, leaf.id);
    assert!(
        c.register_album(root.id.clone(), child.clone(), Some(leaf.id.clone()))
            .await
            .is_err()
    );
    assert_eq!(
        c.albums(Some(root.id.clone()), Some(root.id.clone()))
            .await
            .unwrap()[0]
            .source
            .id,
        child.id
    );
    assert!(c.albums(Some(root.id), None).await.is_err());
}

#[tokio::test]
async fn photos_device_and_drive_sources_cannot_be_reclassified_into_each_other() {
    let c = Catalog::in_memory().await.unwrap();
    let photos = folder("photos");
    c.register_source(photos.clone()).await.unwrap();
    c.set_source_kind(photos.id.clone(), SourceKind::Photos)
        .await
        .unwrap();
    assert!(
        c.set_source_kind(photos.id.clone(), SourceKind::Drive)
            .await
            .is_err()
    );
    assert!(
        c.sources_of_kind(SourceKind::Drive)
            .await
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        c.sources_of_kind(SourceKind::Photos).await.unwrap(),
        vec![photos]
    );
    let root = folder("root");
    c.register_album(root.id.clone(), root.clone(), None)
        .await
        .unwrap();
    let mut foreign = folder("foreign");
    foreign.account = AccountId::new("another").unwrap();
    assert!(
        c.register_album(root.id.clone(), foreign, Some(root.id.clone()))
            .await
            .is_err()
    );
    assert!(
        c.albums(Some(root.id.clone()), Some(root.id))
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn an_album_can_contain_media_and_child_albums_without_flattening_them() {
    use pg_core::source::SourcePage;
    let c = Catalog::in_memory().await.unwrap();
    let root = folder("root");
    let child = folder("child");
    c.register_album(root.id.clone(), root.clone(), None)
        .await
        .unwrap();
    c.register_album(root.id.clone(), child, Some(root.id.clone()))
        .await
        .unwrap();
    let key = AssetKey {
        account: root.account.clone(),
        volume: root.volume.clone(),
        node: NodeId::new("clip").unwrap(),
    };
    let asset = Asset {
        key: key.clone(),
        name: "clip.mp4".into(),
        mime: "video/mp4".into(),
        kind: MediaKind::Video,
        revision: None,
        size_bytes: Some(100),
        sort_time: 1,
    };
    let checkpoint = c.begin_scan(root.id.clone(), false).await.unwrap();
    c.apply_page(
        root.clone(),
        checkpoint,
        SourcePage {
            assets: vec![asset],
            next: None,
        },
    )
    .await
    .unwrap();
    c.remember_shape(key.clone(), 1920, 1080).await.unwrap();
    assert!(c.remember_shape(key.clone(), 0, 1080).await.is_err());
    assert_eq!(
        c.aspect_ratios(vec![key]).await.unwrap(),
        vec![Some(1920.0 / 1080.0)]
    );
    let roots = c.albums(None, None).await.unwrap();
    assert_eq!((roots[0].direct_items, roots[0].child_albums), (1, 1));
    assert_eq!(
        c.page(Some(root.id), None, PageSize::default())
            .await
            .unwrap()
            .items
            .len(),
        1
    );
}

#[tokio::test]
async fn account_filters_apply_before_limits_and_nested_album_queries() {
    let c = Catalog::in_memory().await.unwrap();
    for n in 0..251 {
        let source = folder(&format!("a-other-{n:03}"));
        c.register_source(source.clone()).await.unwrap();
        c.set_source_kind(source.id, SourceKind::Photos)
            .await
            .unwrap();
    }
    let mut mine = folder("z-mine");
    mine.account = AccountId::new("mine").unwrap();
    c.register_source(mine.clone()).await.unwrap();
    c.set_source_kind(mine.id.clone(), SourceKind::Photos)
        .await
        .unwrap();
    assert_eq!(
        c.account_sources(SourceKind::Photos, mine.account.clone())
            .await
            .unwrap(),
        vec![mine.clone()]
    );
    let root = folder("root");
    c.register_album(root.id.clone(), root.clone(), None)
        .await
        .unwrap();
    let mut other = folder("other");
    other.account = mine.account.clone();
    c.register_album(other.id.clone(), other.clone(), None)
        .await
        .unwrap();
    assert_eq!(
        c.albums_scoped(None, None, Some(mine.account.clone()))
            .await
            .unwrap()
            .len(),
        1
    );
    assert!(
        c.albums_scoped(
            Some(root.id.clone()),
            Some(root.id.clone()),
            Some(mine.account.clone())
        )
        .await
        .unwrap()
        .is_empty()
    );
    assert!(
        !c.source_belongs_to(root.id, mine.account.clone())
            .await
            .unwrap()
    );
    assert!(c.source_belongs_to(mine.id, mine.account).await.unwrap());
}

fn media(source: &SourceSpec, node: &str, time: i64) -> Asset {
    Asset {
        key: AssetKey {
            account: source.account.clone(),
            volume: source.volume.clone(),
            node: NodeId::new(node).unwrap(),
        },
        name: format!("{node}.jpg"),
        mime: "image/jpeg".into(),
        kind: MediaKind::Image,
        revision: None,
        size_bytes: Some(1),
        sort_time: time,
    }
}

async fn fill(c: &Catalog, source: &SourceSpec, assets: Vec<Asset>) {
    let checkpoint = c.begin_scan(source.id.clone(), true).await.unwrap();
    c.apply_page(
        source.clone(),
        checkpoint,
        pg_core::source::SourcePage { assets, next: None },
    )
    .await
    .unwrap();
}

#[tokio::test]
async fn album_settings_name_cover_group_tag_pin_and_hide_albums() {
    use pg_core::library::{AlbumSettings, Grouping};
    let c = Catalog::in_memory().await.unwrap();
    let (root, beach, city) = (folder("root"), folder("beach"), folder("city"));
    c.register_album(root.id.clone(), root.clone(), None)
        .await
        .unwrap();
    c.register_album(root.id.clone(), beach.clone(), Some(root.id.clone()))
        .await
        .unwrap();
    c.register_album(root.id.clone(), city.clone(), Some(root.id.clone()))
        .await
        .unwrap();
    fill(
        &c,
        &beach,
        vec![media(&beach, "b1", 10), media(&beach, "b2", 20)],
    )
    .await;
    fill(&c, &city, vec![media(&city, "c1", 30)]).await;

    // Defaults: folder name, newest item as cover, day groups.
    let albums = c
        .albums(Some(root.id.clone()), Some(root.id.clone()))
        .await
        .unwrap();
    assert_eq!(albums[0].source.id, beach.id);
    assert_eq!(albums[0].settings, AlbumSettings::default());
    assert_eq!(albums[0].cover.as_ref().unwrap().key.node.as_str(), "b2");
    // An album of albums takes its cover from below.
    let roots = c.albums(None, None).await.unwrap();
    assert_eq!(roots[0].cover.as_ref().unwrap().key.node.as_str(), "c1");

    let saved = c
        .set_album_settings(
            beach.id.clone(),
            AlbumSettings {
                title: Some("  Summer  ".into()),
                description: Some(" ".into()),
                cover: Some(media(&beach, "b1", 0).key),
                grouping: Grouping::None,
                order: SortOrder::Oldest,
                pinned: false,
                hidden: false,
                tags: vec![
                    "Trip".into(),
                    " trip ".into(),
                    "Family  time".into(),
                    "".into(),
                ],
            },
        )
        .await
        .unwrap();
    assert_eq!(saved.title.as_deref(), Some("Summer"));
    assert_eq!(saved.description, None);
    assert_eq!(
        saved.tags,
        vec!["Family time".to_string(), "Trip".to_string()]
    );
    assert_eq!(c.album_settings(beach.id.clone()).await.unwrap(), saved);

    // A cover from another album is refused.
    let foreign = AlbumSettings {
        cover: Some(media(&city, "c1", 0).key),
        ..saved.clone()
    };
    assert!(
        c.set_album_settings(beach.id.clone(), foreign)
            .await
            .is_err()
    );
    let too_many = AlbumSettings {
        tags: (0..21).map(|i| format!("t{i}")).collect(),
        ..saved.clone()
    };
    assert!(
        c.set_album_settings(beach.id.clone(), too_many)
            .await
            .is_err()
    );

    // Sorted by display name; the chosen cover wins.
    c.set_album_settings(
        city.id.clone(),
        AlbumSettings {
            title: Some("Alpha".into()),
            tags: vec!["trip".into()],
            hidden: true,
            ..Default::default()
        },
    )
    .await
    .unwrap();
    let albums = c
        .albums(Some(root.id.clone()), Some(root.id.clone()))
        .await
        .unwrap();
    assert_eq!(albums[0].source.id, city.id);
    assert!(albums[0].settings.hidden);
    assert_eq!(albums[1].cover.as_ref().unwrap().key.node.as_str(), "b1");
    // Pinned albums come first.
    c.set_album_settings(
        beach.id.clone(),
        AlbumSettings {
            pinned: true,
            ..saved.clone()
        },
    )
    .await
    .unwrap();
    let albums = c
        .albums(Some(root.id.clone()), Some(root.id.clone()))
        .await
        .unwrap();
    assert_eq!(albums[0].source.id, beach.id);

    let account = root.account.clone();
    let tags = c.album_tags(account.clone()).await.unwrap();
    assert_eq!(
        tags,
        vec![("Family time".to_string(), 1), ("Trip".to_string(), 2)]
    );
    assert_eq!(
        c.tagged_albums("TRIP".into(), account.clone())
            .await
            .unwrap()
            .len(),
        2
    );
    assert!(
        c.tagged_albums("trip".into(), AccountId::new("someone-else").unwrap())
            .await
            .unwrap()
            .is_empty()
    );

    // Removing a gallery forgets its tree, not its media or settings.
    c.remove_gallery(root.id.clone(), account.clone())
        .await
        .unwrap();
    assert!(c.albums(None, None).await.unwrap().is_empty());
    assert_eq!(
        c.album_settings(beach.id.clone())
            .await
            .unwrap()
            .title
            .as_deref(),
        Some("Summer")
    );
    assert!(c.remove_gallery(root.id.clone(), account).await.is_err());
}

#[tokio::test]
async fn oldest_first_pages_forward_and_back_and_rejects_foreign_cursors() {
    let c = Catalog::in_memory().await.unwrap();
    let album = folder("album");
    c.register_album(album.id.clone(), album.clone(), None)
        .await
        .unwrap();
    fill(
        &c,
        &album,
        (0..7)
            .map(|i| media(&album, &format!("n{i}"), i * 10))
            .collect(),
    )
    .await;
    let size = PageSize::new(3).unwrap();
    let id = Some(album.id.clone());
    let first = c
        .window_ordered(id.clone(), None, false, SortOrder::Oldest, size)
        .await
        .unwrap();
    let times = |w: &TimelineWindow| w.items.iter().map(|a| a.sort_time).collect::<Vec<_>>();
    assert_eq!(times(&first), vec![0, 10, 20]);
    let second = c
        .window_ordered(
            id.clone(),
            first.next.clone(),
            false,
            SortOrder::Oldest,
            size,
        )
        .await
        .unwrap();
    assert_eq!(times(&second), vec![30, 40, 50]);
    let back = c
        .window_ordered(
            id.clone(),
            second.previous.clone(),
            true,
            SortOrder::Oldest,
            size,
        )
        .await
        .unwrap();
    assert_eq!(times(&back), vec![0, 10, 20]);
    // A newest-first cursor cannot continue an oldest-first listing.
    let newest = c.window(id.clone(), None, false, size).await.unwrap();
    assert_eq!(times(&newest), vec![60, 50, 40]);
    assert!(
        c.window_ordered(id, newest.next, false, SortOrder::Oldest, size)
            .await
            .is_err()
    );
}

#[tokio::test]
async fn name_order_ignores_case_pages_both_ways_and_follows_renames() {
    let c = Catalog::in_memory().await.unwrap();
    let album = folder("album");
    c.register_album(album.id.clone(), album.clone(), None)
        .await
        .unwrap();
    let named = |node: &str, name: &str, time: i64| Asset {
        name: name.into(),
        ..media(&album, node, time)
    };
    // Equal names fall back to the asset key, so paging stays total.
    let assets = vec![
        named("a", "beach.jpg", 50),
        named("b", "Apple.jpg", 10),
        named("c", "cat.jpg", 40),
        named("d", "apple.jpg", 30),
        named("e", "Dog.jpg", 20),
        named("f", "egg.jpg", 60),
    ];
    fill(&c, &album, assets.clone()).await;
    let size = PageSize::new(4).unwrap();
    let id = Some(album.id.clone());
    let names = |w: &TimelineWindow| w.items.iter().map(|a| a.name.clone()).collect::<Vec<_>>();
    let first = c
        .window_ordered(id.clone(), None, false, SortOrder::Name, size)
        .await
        .unwrap();
    assert_eq!(
        names(&first),
        ["Apple.jpg", "apple.jpg", "beach.jpg", "cat.jpg"]
    );
    let second = c
        .window_ordered(id.clone(), first.next.clone(), false, SortOrder::Name, size)
        .await
        .unwrap();
    assert_eq!(names(&second), ["Dog.jpg", "egg.jpg"]);
    assert!(second.next.is_none());
    let back = c
        .window_ordered(
            id.clone(),
            second.previous.clone(),
            true,
            SortOrder::Name,
            size,
        )
        .await
        .unwrap();
    assert_eq!(names(&back), names(&first));
    let descending = c
        .window_ordered(id.clone(), None, false, SortOrder::NameDesc, size)
        .await
        .unwrap();
    assert_eq!(
        names(&descending),
        ["egg.jpg", "Dog.jpg", "cat.jpg", "beach.jpg"]
    );
    // The whole catalog pages by name through its own index.
    let all = c
        .window_ordered(
            None,
            None,
            false,
            SortOrder::Name,
            PageSize::new(10).unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(all.items.len(), 6);
    assert_eq!(all.items[5].name, "egg.jpg");
    assert!(
        c.window_ordered(id.clone(), first.next, false, SortOrder::Newest, size)
            .await
            .is_err()
    );

    // A renamed file moves, and the default cover follows the album order.
    let mut renamed = assets.clone();
    renamed[5] = named("f", "aardvark.jpg", 60);
    fill(&c, &album, renamed).await;
    let first = c
        .window_ordered(id.clone(), None, false, SortOrder::Name, size)
        .await
        .unwrap();
    assert_eq!(first.items[0].name, "aardvark.jpg");
    c.set_album_settings(
        album.id.clone(),
        pg_core::library::AlbumSettings {
            order: SortOrder::NameDesc,
            ..Default::default()
        },
    )
    .await
    .unwrap();
    let albums = c.albums(None, None).await.unwrap();
    assert_eq!(albums[0].cover.as_ref().unwrap().name, "Dog.jpg");
}
