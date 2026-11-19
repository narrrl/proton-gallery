//! In-app Linux playback. The decoder reads Proton ranges without a temporary file.
use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use gstreamer_video as gst_video;
use pg_app::video::VideoStream;
use std::sync::{Arc, Mutex};

type FrameSlot = Arc<Mutex<Option<Result<crate::thumbs::Decoded, String>>>>;

pub struct Player {
    pipeline: gst::Element,
    frame: FrameSlot,
    stream: Option<Arc<VideoStream>>,
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
        let frame = Arc::new(Mutex::new(None));
        let output = frame.clone();
        let preroll = frame.clone();
        sink.set_callbacks(
            gst_app::AppSinkCallbacks::builder()
                .new_sample(move |sink| {
                    let sample = sink.pull_sample().map_err(|_| gst::FlowError::Flushing)?;
                    *output.lock().unwrap_or_else(|e| e.into_inner()) = Some(decode(sample));
                    Ok(gst::FlowSuccess::Ok)
                })
                .new_preroll(move |sink| {
                    let sample = sink.pull_preroll().map_err(|_| gst::FlowError::Flushing)?;
                    *preroll.lock().unwrap_or_else(|e| e.into_inner()) = Some(decode(sample));
                    Ok(gst::FlowSuccess::Ok)
                })
                .build(),
        );
        let pipeline = gst::ElementFactory::make("playbin")
            .property("video-sink", &sink)
            .build()
            .map_err(|_| "GStreamer playback plugins are missing.".to_owned())?;
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

    pub fn poll(&self, state: &mut Playback) -> Result<Option<crate::thumbs::Decoded>, String> {
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
                    MessageView::Error(error) => {
                        tracing::debug!("video decoder: {}", error.error());
                        return Err(
                            "Couldn’t play this video. Check your connection and installed codecs."
                                .into(),
                        );
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
        self.frame
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take()
            .transpose()
    }
}

fn decode(sample: gst::Sample) -> Result<crate::thumbs::Decoded, String> {
    let info = gst_video::VideoInfo::from_caps(sample.caps().ok_or("Video frame has no format.")?)
        .map_err(|_| "Unsupported video frame format.")?;
    let width = info.width();
    let height = info.height();
    if width == 0 || height == 0 || width > 4096 || height > 4096 {
        return Err("Video exceeds the supported resolution.".into());
    }
    let frame = gst_video::VideoFrameRef::from_buffer_ref_readable(
        sample.buffer().ok_or("Video frame has no pixels.")?,
        &info,
    )
    .map_err(|_| "Couldn’t read the decoded video frame.")?;
    use gst_video::VideoFrameExt;
    let stride =
        usize::try_from(frame.plane_stride()[0]).map_err(|_| "Unsupported video frame layout.")?;
    let row = width as usize * 4;
    let plane = frame
        .plane_data(0)
        .map_err(|_| "Couldn’t read decoded video pixels.")?;
    if stride < row || plane.len() < stride * (height as usize - 1) + row {
        return Err("Decoded video frame is incomplete.".into());
    }
    let mut pixels = Vec::with_capacity(row * height as usize);
    for y in 0..height as usize {
        pixels.extend_from_slice(&plane[y * stride..y * stride + row]);
    }
    Ok(crate::thumbs::Decoded {
        handle: iced::widget::image::Handle::from_rgba(width, height, pixels),
        width,
        height,
        bytes: row * height as usize,
    })
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

    #[tokio::test]
    async fn gstreamer_decodes_and_seeks_a_webm() {
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
        let deadline = Instant::now() + Duration::from_secs(10);
        let frame = loop {
            if let Some(frame) = player.poll(&mut state).unwrap() {
                break frame;
            }
            assert!(Instant::now() < deadline, "No decoded frames");
            tokio::time::sleep(Duration::from_millis(20)).await;
        };
        assert_eq!((frame.width, frame.height), (128, 72));
        assert_eq!(frame.bytes, 128 * 72 * 4);
        player.set_playing(false).unwrap();
        player.volume(0.5);
        player.seek(1.0).unwrap();
        assert!(state.duration >= 1.5, "duration: {}", state.duration);
    }
}
