//! In-app Linux playback. The decoder reads Proton ranges without a temporary file.
use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use gstreamer_pbutils as gst_pbutils;
use proton_gallery_app_rs::video::VideoStream;
use std::sync::{Arc, Mutex};

use crate::video_surface::FrameSlot;

pub struct Player {
    pipeline: gst::Element,
    frame: Arc<FrameSlot>,
    stream: Option<Arc<VideoStream>>,
    /// Codecs GStreamer looked for and did not find, in Proton's words for
    /// them ("H.265 (Main Profile) decoder").
    missing: Mutex<Vec<String>>,
}

impl std::fmt::Debug for Player {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Player").finish_non_exhaustive()
    }
}

#[derive(Default)]
pub struct Playback {
    pub playing: bool,
    pub buffering: bool,
    pub position: f64,
    pub duration: f64,
    pub volume: f64,
    pub ended: bool,
}

impl Player {
    pub fn open(
        stream: Option<Arc<VideoStream>>,
        local: Option<&std::path::Path>,
        runtime: tokio::runtime::Handle,
    ) -> Result<Self, String> {
        gst::init().map_err(|error| error.to_string())?;
        let caps = gst::Caps::builder("video/x-raw")
            .field("format", "RGBA")
            .build();
        let sink = gst_app::AppSink::builder()
            .caps(&caps)
            .max_buffers(1)
            .drop(true)
            .sync(true)
            .build();
        // Samples go to the GPU as they are; no copy on this side.
        let frame = Arc::new(FrameSlot::default());
        let output = frame.clone();
        let preroll = frame.clone();
        sink.set_callbacks(
            gst_app::AppSinkCallbacks::builder()
                .new_sample(move |sink| {
                    let sample = sink.pull_sample().map_err(|_| gst::FlowError::Flushing)?;
                    output.store(sample);
                    Ok(gst::FlowSuccess::Ok)
                })
                .new_preroll(move |sink| {
                    let sample = sink.pull_preroll().map_err(|_| gst::FlowError::Flushing)?;
                    preroll.store(sample);
                    Ok(gst::FlowSuccess::Ok)
                })
                .build(),
        );
        let pipeline = gst::ElementFactory::make("playbin")
            .property("video-sink", &sink)
            .build()
            .map_err(|_| "GStreamer playback plugins are missing.".to_owned())?;
        // Phones record upright video as landscape frames plus a rotation tag;
        // playbin hands frames over as stored, so turn them as the tag says.
        match upright_filter() {
            Some(filter) => pipeline.set_property("video-filter", &filter),
            None => tracing::warn!("videoflip is missing; rotated phone videos play sideways"),
        }
        if let Some(path) = local {
            let uri = gst::glib::filename_to_uri(path, None)
                .map_err(|_| "Couldn’t open this video file.".to_owned())?;
            pipeline.set_property("uri", uri);
        } else if let Some(stream) = &stream {
            let size = i64::try_from(stream.length()).map_err(|_| "This video is too large.")?;
            let stream = stream.clone();
            pipeline.connect("source-setup", false, move |args| {
                if let Ok(source) = args[1].get::<gst_app::AppSrc>() {
                    bind_source(&source, stream.clone(), size, runtime.clone());
                }
                None
            });
            pipeline.set_property("uri", "appsrc://proton-gallery");
        } else {
            return Err("This video has no playable source.".into());
        }
        let player = Self {
            pipeline,
            frame,
            stream,
            missing: Mutex::default(),
        };
        player.set_playing(true)?;
        Ok(player)
    }

    pub fn set_playing(&self, playing: bool) -> Result<(), String> {
        self.pipeline
            .set_state(if playing {
                gst::State::Playing
            } else {
                gst::State::Paused
            })
            .map_err(|_| "Couldn’t change video playback state.".to_owned())?;
        Ok(())
    }

    pub fn seek(&self, position: f64) -> Result<(), String> {
        self.pipeline
            .seek_simple(
                gst::SeekFlags::FLUSH | gst::SeekFlags::KEY_UNIT,
                gst::ClockTime::from_mseconds((position * 1000.0).round() as u64),
            )
            .map_err(|_| "Couldn’t seek in this video.".into())
    }

    pub fn volume(&self, volume: f64) {
        self.pipeline.set_property("volume", volume.clamp(0.0, 1.0));
    }

    /// The frames, for the video surface.
    pub fn frames(&self) -> Arc<FrameSlot> {
        self.frame.clone()
    }

    pub fn poll(&self, state: &mut Playback) -> Result<(), String> {
        if let Some(bus) = self.pipeline.bus() {
            for message in bus.iter() {
                use gst::MessageView;
                match message.view() {
                    MessageView::Buffering(buffer) => {
                        let buffering = buffer.percent() < 100;
                        if state.buffering != buffering && state.playing && !state.ended {
                            self.set_playing(!buffering)?;
                        }
                        state.buffering = buffering;
                    }
                    MessageView::Eos(_) => {
                        state.ended = true;
                        state.playing = false;
                    }
                    MessageView::Element(_) => {
                        let Ok(missing) = gst_pbutils::MissingPluginMessage::parse(&message) else {
                            continue;
                        };
                        let description = missing.description().to_string();
                        let detail = missing.installer_detail();
                        tracing::warn!("GStreamer plugin missing: {description} ({detail})");
                        self.missing
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .push(description.clone());
                        // playbin carries on without a stream it can't decode:
                        // silently for audio, which is fine, but a video that
                        // never shows a frame needs saying why.
                        if detail.contains("decoder-video/") {
                            return Err(missing_codec(Some(&description)));
                        }
                    }
                    MessageView::Error(error) => {
                        tracing::warn!(
                            "video playback failed: {} ({:?})",
                            error.error(),
                            error.debug()
                        );
                        return Err(self.explain(error.error()));
                    }
                    _ => {}
                }
            }
        }
        if let Some(time) = self.pipeline.query_duration::<gst::ClockTime>() {
            state.duration = time.mseconds() as f64 / 1000.0;
        }
        if let Some(time) = self.pipeline.query_position::<gst::ClockTime>() {
            state.position = time.mseconds() as f64 / 1000.0;
        }
        Ok(())
    }

    /// Say what went wrong in terms the user can act on.
    fn explain(&self, error: gst::glib::Error) -> String {
        let missing = self.missing.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(description) = missing.first() {
            return missing_codec(Some(description));
        }
        if error.matches(gst::StreamError::CodecNotFound)
            || error.matches(gst::StreamError::TypeNotFound)
            || error.matches(gst::StreamError::WrongType)
        {
            return missing_codec(None);
        }
        if error.matches(gst::ResourceError::Read) {
            return "Couldn’t load this video. Check your connection.".into();
        }
        "Couldn’t play this video.".into()
    }
}

fn missing_codec(description: Option<&str>) -> String {
    match description {
        Some(description) => format!(
            "This video needs the {description}, which isn’t installed. \
             Install GStreamer’s gst-plugins-bad and gst-libav."
        ),
        None => "This video’s format isn’t supported by the installed GStreamer plugins. \
                 Install gst-plugins-bad and gst-libav."
            .into(),
    }
}

/// A `videoflip` that follows the stream's `image-orientation` tag.
fn upright_filter() -> Option<gst::Element> {
    let flip = gst::ElementFactory::make("videoflip").build().ok()?;
    flip.set_property_from_str("video-direction", "auto");
    Some(flip)
}

impl Drop for Player {
    fn drop(&mut self) {
        if let Some(stream) = &self.stream {
            stream.stop();
        }
        let _ = self.pipeline.set_state(gst::State::Null);
    }
}

fn bind_source(
    source: &gst_app::AppSrc,
    stream: Arc<VideoStream>,
    size: i64,
    runtime: tokio::runtime::Handle,
) {
    let offset = Arc::new(Mutex::new(0u64));
    let seek = offset.clone();
    source.set_format(gst::Format::Bytes);
    source.set_stream_type(gst_app::AppStreamType::RandomAccess);
    source.set_size(size);
    source.set_callbacks(
        gst_app::AppSrcCallbacks::builder()
            .seek_data(move |_, position| {
                *seek.lock().unwrap_or_else(|e| e.into_inner()) = position;
                position <= size as u64
            })
            .need_data(move |source, wanted| {
                let mut offset = offset.lock().unwrap_or_else(|e| e.into_inner());
                let wanted = u64::from(wanted).min((size as u64).saturating_sub(*offset)) as usize;
                if wanted == 0 {
                    let _ = source.end_of_stream();
                    return;
                }
                if wanted > 8 * 1024 * 1024 {
                    gst::element_error!(
                        source,
                        gst::ResourceError::Read,
                        ("Video decoder requested too much data")
                    );
                    return;
                }
                let mut bytes = Vec::with_capacity(wanted);
                while bytes.len() < wanted {
                    match runtime.block_on(stream.read(*offset, (wanted - bytes.len()) as u32)) {
                        Ok(chunk) if !chunk.is_empty() => {
                            *offset += chunk.len() as u64;
                            bytes.extend_from_slice(&chunk);
                        }
                        _ => {
                            gst::element_error!(
                                source,
                                gst::ResourceError::Read,
                                ("Couldn’t read the Proton video")
                            );
                            return;
                        }
                    }
                }
                let mut buffer = gst::Buffer::from_mut_slice(bytes);
                buffer
                    .get_mut()
                    .unwrap()
                    .set_offset(*offset - wanted as u64);
                let _ = source.push_buffer(buffer);
            })
            .build(),
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    /// The GStreamer tests decode real video. Run one at a time so a busy
    /// CI runner does not starve the small clip while the HD one decodes.
    static GSTREAMER: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

    #[tokio::test]
    async fn gstreamer_decodes_and_seeks_a_webm() {
        let _serial = GSTREAMER.lock().await;
        gst::init().unwrap();
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("clip.webm");
        let producer = gst::parse::launch(
            "videotestsrc num-buffers=60 ! video/x-raw,width=128,height=72,framerate=30/1 ! vp8enc deadline=1 ! webmmux ! filesink name=output",
        ).unwrap().downcast::<gst::Pipeline>().unwrap();
        producer
            .by_name("output")
            .unwrap()
            .set_property("location", path.to_str().unwrap());
        producer.set_state(gst::State::Playing).unwrap();
        let bus = producer.bus().unwrap();
        let status = bus.timed_pop_filtered(
            gst::ClockTime::from_seconds(10),
            &[gst::MessageType::Eos, gst::MessageType::Error],
        );
        producer.set_state(gst::State::Null).unwrap();
        assert_eq!(status.unwrap().type_(), gst::MessageType::Eos);
        let player = Player::open(None, Some(&path), tokio::runtime::Handle::current()).unwrap();
        let mut state = Playback {
            playing: true,
            ..Playback::default()
        };
        let deadline = Instant::now() + Duration::from_secs(30);
        let frames = player.frames();
        while !frames.has_frame() {
            player.poll(&mut state).unwrap();
            assert!(Instant::now() < deadline, "No decoded frames");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert_eq!(frames.size(), Some((128, 72)));
        player.poll(&mut state).unwrap();
        player.set_playing(false).unwrap();
        player.volume(0.5);
        player.seek(1.0).unwrap();
        assert!(state.duration >= 1.5, "duration: {}", state.duration);
    }

    /// Encode a test clip with `encoder` into `path`.
    fn encode(path: &std::path::Path, size: (u32, u32), frames: u32, encoder: &str) {
        let producer = gst::parse::launch(&format!(
            "videotestsrc num-buffers={frames} ! video/x-raw,width={},height={},framerate=30/1 ! {encoder} ! filesink name=output",
            size.0, size.1
        ))
        .unwrap()
        .downcast::<gst::Pipeline>()
        .unwrap();
        producer
            .by_name("output")
            .unwrap()
            .set_property("location", path.to_str().unwrap());
        producer.set_state(gst::State::Playing).unwrap();
        let status = producer.bus().unwrap().timed_pop_filtered(
            gst::ClockTime::from_seconds(60),
            &[gst::MessageType::Eos, gst::MessageType::Error],
        );
        producer.set_state(gst::State::Null).unwrap();
        assert_eq!(status.unwrap().type_(), gst::MessageType::Eos);
    }

    /// HD frames are what flickered: iced uploaded images over 2 MiB late.
    /// They now reach the frame slot whole, each under a new number.
    #[tokio::test]
    async fn full_hd_frames_reach_the_video_surface() {
        let _serial = GSTREAMER.lock().await;
        gst::init().unwrap();
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("hd.webm");
        encode(&path, (1920, 1080), 20, "vp8enc deadline=1 ! webmmux");
        let player = Player::open(None, Some(&path), tokio::runtime::Handle::current()).unwrap();
        let mut state = Playback {
            playing: true,
            ..Playback::default()
        };
        let frames = player.frames();
        let deadline = Instant::now() + Duration::from_secs(60);
        let mut seen = std::collections::BTreeSet::new();
        while seen.len() < 3 {
            player.poll(&mut state).unwrap();
            if frames.has_frame() {
                assert_eq!(frames.size(), Some((1920, 1080)));
                seen.insert(frames.generation());
            }
            assert!(Instant::now() < deadline, "frames seen: {seen:?}");
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    #[tokio::test]
    async fn a_missing_video_decoder_is_named() {
        let _serial = GSTREAMER.lock().await;
        gst::init().unwrap();
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("clip.webm");
        encode(&path, (64, 36), 5, "vp8enc deadline=1 ! webmmux");
        let player = Player::open(None, Some(&path), tokio::runtime::Handle::current()).unwrap();
        player.set_playing(false).unwrap();
        let caps = gst::Caps::builder("video/x-h265").build();
        let message = gst_pbutils::MissingPluginMessage::builder_for_decoder(&caps)
            .src(&player.pipeline)
            .build();
        player.pipeline.post_message(message).unwrap();
        let error = player.poll(&mut Playback::default()).unwrap_err();
        assert!(error.contains("H.265"), "{error}");
        assert!(error.contains("gst-plugins-bad"), "{error}");
    }

    #[test]
    fn read_failures_are_not_blamed_on_codecs() {
        gst::init().unwrap();
        let player = Player {
            pipeline: gst::ElementFactory::make("playbin").build().unwrap(),
            frame: Arc::default(),
            stream: None,
            missing: Mutex::default(),
        };
        let read = gst::glib::Error::new(gst::ResourceError::Read, "offline");
        assert!(player.explain(read).contains("connection"));
        let codec = gst::glib::Error::new(gst::StreamError::CodecNotFound, "no decoder");
        assert!(player.explain(codec).contains("gst-libav"));
    }
}
