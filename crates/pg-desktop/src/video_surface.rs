//! Draws decoded video frames straight into one GPU texture.
//!
//! An `image::Handle` per frame does not work for video: every frame is a new
//! image to iced, and iced uploads images over 2 MiB (anything from about
//! 960×540 in RGBA) on a background worker, drawing nothing until the upload
//! is done. Each HD frame then showed as a blank frame first: the flicker.
//! Here one texture lives as long as the video, each new frame is written into
//! it in place, and the previous frame stays on screen until then.
use gstreamer as gst;
use gstreamer_video as gst_video;
use iced::wgpu;
use iced::widget::shader::{self, Viewport};
use iced::{Rectangle, mouse};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

/// Frame numbers unique across players, so a new video never matches the
/// number of the last frame the texture holds from the previous one.
static NEXT_FRAME: AtomicU64 = AtomicU64::new(1);

/// The newest decoded frame, numbered so the GPU side uploads each one once.
#[derive(Debug, Default)]
pub struct FrameSlot {
    latest: Mutex<Option<(u64, gst::Sample)>>,
}

impl FrameSlot {
    /// Keep `sample` as the newest frame. Called on GStreamer's thread.
    pub fn store(&self, sample: gst::Sample) {
        let generation = NEXT_FRAME.fetch_add(1, Ordering::Relaxed);
        *self.latest.lock().unwrap_or_else(|e| e.into_inner()) = Some((generation, sample));
    }

    pub fn has_frame(&self) -> bool {
        self.latest
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some()
    }

    fn latest(&self) -> Option<(u64, gst::Sample)> {
        self.latest
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    #[cfg(test)]
    pub fn generation(&self) -> u64 {
        self.latest().map_or(0, |(generation, _)| generation)
    }

    #[cfg(test)]
    pub fn size(&self) -> Option<(u32, u32)> {
        let (_, sample) = self.latest()?;
        let info = gst_video::VideoInfo::from_caps(sample.caps()?).ok()?;
        Some((info.width(), info.height()))
    }
}

/// The shader widget's program: shows whatever frame the slot holds.
#[derive(Debug, Clone)]
pub struct VideoProgram {
    slot: Arc<FrameSlot>,
}

impl VideoProgram {
    pub fn new(slot: Arc<FrameSlot>) -> Self {
        Self { slot }
    }
}

impl<Message> shader::Program<Message> for VideoProgram {
    type State = ();
    type Primitive = VideoPrimitive;

    fn draw(&self, _state: &(), _cursor: mouse::Cursor, _bounds: Rectangle) -> VideoPrimitive {
        VideoPrimitive {
            frame: self.slot.latest(),
        }
    }
}

#[derive(Debug)]
pub struct VideoPrimitive {
    frame: Option<(u64, gst::Sample)>,
}

impl shader::Primitive for VideoPrimitive {
    type Pipeline = VideoPipeline;

    fn prepare(
        &self,
        pipeline: &mut VideoPipeline,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        bounds: &Rectangle,
        _viewport: &Viewport,
    ) {
        let Some((generation, sample)) = &self.frame else {
            return;
        };
        if pipeline.uploaded != Some(*generation) {
            pipeline.uploaded = Some(*generation);
            if let Err(error) = pipeline.upload(device, queue, sample) {
                tracing::warn!("video frame not shown: {error}");
            }
        }
        let Some(texture) = &pipeline.texture else {
            return;
        };
        // Fit the frame into the widget, keeping its shape (letterboxing).
        let frame = texture.width as f32 / texture.height as f32;
        let widget = bounds.width / bounds.height.max(1.0);
        let scale = if frame > widget {
            [1.0, widget / frame]
        } else {
            [frame / widget, 1.0]
        };
        queue.write_buffer(
            &pipeline.uniforms,
            0,
            &f32_bytes(&[scale[0], scale[1], 0.0, 0.0]),
        );
    }

    fn draw(&self, pipeline: &VideoPipeline, render_pass: &mut wgpu::RenderPass<'_>) -> bool {
        if self.frame.is_none() {
            return true;
        }
        let Some(texture) = &pipeline.texture else {
            return true;
        };
        render_pass.set_pipeline(&pipeline.pipeline);
        render_pass.set_bind_group(0, &texture.bind_group, &[]);
        render_pass.draw(0..4, 0..1);
        true
    }
}

struct FrameTexture {
    texture: wgpu::Texture,
    bind_group: wgpu::BindGroup,
    width: u32,
    height: u32,
}

pub struct VideoPipeline {
    pipeline: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    uniforms: wgpu::Buffer,
    format: wgpu::TextureFormat,
    texture: Option<FrameTexture>,
    uploaded: Option<u64>,
}

impl shader::Pipeline for VideoPipeline {
    fn new(device: &wgpu::Device, _queue: &wgpu::Queue, target: wgpu::TextureFormat) -> Self {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("video frame"),
            source: wgpu::ShaderSource::Wgsl(SHADER.into()),
        });
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("video frame"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::VERTEX,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
            ],
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("video frame"),
            bind_group_layouts: &[&layout],
            push_constant_ranges: &[],
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("video frame"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &module,
                entry_point: Some("vs_main"),
                compilation_options: wgpu::PipelineCompilationOptions::default(),
                buffers: &[],
            },
            fragment: Some(wgpu::FragmentState {
                module: &module,
                entry_point: Some("fs_main"),
                compilation_options: wgpu::PipelineCompilationOptions::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format: target,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: wgpu::PrimitiveState {
                topology: wgpu::PrimitiveTopology::TriangleStrip,
                ..wgpu::PrimitiveState::default()
            },
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            multiview: None,
            cache: None,
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("video frame"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..wgpu::SamplerDescriptor::default()
        });
        let uniforms = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("video frame fit"),
            size: 16,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Self {
            pipeline,
            layout,
            sampler,
            uniforms,
            // Sample as sRGB when the target is sRGB, so colours round-trip.
            format: if target.is_srgb() {
                wgpu::TextureFormat::Rgba8UnormSrgb
            } else {
                wgpu::TextureFormat::Rgba8Unorm
            },
            texture: None,
            uploaded: None,
        }
    }
}

impl VideoPipeline {
    /// Write `sample` into the frame texture, replacing the texture only when
    /// the video's size changes.
    fn upload(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        sample: &gst::Sample,
    ) -> Result<(), String> {
        let caps = sample.caps().ok_or("frame has no format")?;
        let info = gst_video::VideoInfo::from_caps(caps).map_err(|_| "unsupported frame format")?;
        let (width, height) = (info.width(), info.height());
        let limit = device.limits().max_texture_dimension_2d;
        if width == 0 || height == 0 || width > limit || height > limit {
            return Err(format!("{width}×{height} exceeds the GPU limit of {limit}"));
        }
        let frame = gst_video::VideoFrameRef::from_buffer_ref_readable(
            sample.buffer().ok_or("frame has no pixels")?,
            &info,
        )
        .map_err(|_| "frame is not readable")?;
        use gst_video::VideoFrameExt;
        let stride = u32::try_from(frame.plane_stride()[0]).map_err(|_| "negative stride")?;
        let pixels = frame.plane_data(0).map_err(|_| "frame has no pixels")?;
        let row = width as usize * 4;
        if (stride as usize) < row || pixels.len() < stride as usize * (height as usize - 1) + row {
            return Err("frame is incomplete".into());
        }
        if self
            .texture
            .as_ref()
            .is_none_or(|texture| (texture.width, texture.height) != (width, height))
        {
            self.texture = Some(self.create_texture(device, width, height));
        }
        let Some(texture) = &self.texture else {
            return Ok(());
        };
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &texture.texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            pixels,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(stride),
                rows_per_image: Some(height),
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        Ok(())
    }

    fn create_texture(&self, device: &wgpu::Device, width: u32, height: u32) -> FrameTexture {
        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("video frame"),
            size: wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: self.format,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
        let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("video frame"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&view),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: self.uniforms.as_entire_binding(),
                },
            ],
        });
        FrameTexture {
            texture,
            bind_group,
            width,
            height,
        }
    }
}

fn f32_bytes(values: &[f32; 4]) -> [u8; 16] {
    let mut bytes = [0; 16];
    for (chunk, value) in bytes.chunks_exact_mut(4).zip(values) {
        chunk.copy_from_slice(&value.to_ne_bytes());
    }
    bytes
}

/// A quad over the widget, shrunk by `fit` to the frame's shape.
const SHADER: &str = r"
struct Fit { scale: vec4<f32> };

@group(0) @binding(0) var frame: texture_2d<f32>;
@group(0) @binding(1) var frame_sampler: sampler;
@group(0) @binding(2) var<uniform> fit: Fit;

struct Out {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) index: u32) -> Out {
    let uv = vec2<f32>(f32(index & 1u), f32(index >> 1u));
    var out: Out;
    let corner = vec2<f32>(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    out.position = vec4<f32>(corner * fit.scale.xy, 0.0, 1.0);
    out.uv = uv;
    return out;
}

@fragment
fn fs_main(input: Out) -> @location(0) vec4<f32> {
    return textureSample(frame, frame_sampler, input.uv);
}
";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_shader_validates() {
        let module = naga::front::wgsl::parse_str(SHADER).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
    }

    #[test]
    fn frame_numbers_never_repeat_across_players() {
        gst::init().unwrap();
        let sample = || gst::Sample::builder().build();
        let (first, second) = (FrameSlot::default(), FrameSlot::default());
        first.store(sample());
        let a = first.generation();
        second.store(sample());
        assert_ne!(a, second.generation());
        assert!(!FrameSlot::default().has_frame());
    }

    /// Runs the pipeline on a real GPU: wgpu validates every call, so a bad
    /// binding or format fails here instead of when a video opens. Skipped on
    /// machines without an adapter (CI).
    #[tokio::test]
    async fn a_frame_renders_on_the_gpu() {
        use shader::{Pipeline, Primitive};
        gst::init().unwrap();
        let instance = wgpu::Instance::default();
        let Ok(adapter) = instance
            .request_adapter(&wgpu::RequestAdapterOptions::default())
            .await
        else {
            eprintln!("no GPU adapter; skipped");
            return;
        };
        let (device, queue) = adapter
            .request_device(&wgpu::DeviceDescriptor::default())
            .await
            .unwrap();
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let format = wgpu::TextureFormat::Bgra8UnormSrgb;
        let mut pipeline = VideoPipeline::new(&device, &queue, format);
        // A 1920×1080 RGBA frame with a padded stride, as decoders hand out.
        let (width, height, stride) = (1920u32, 1080u32, 1920 * 4 + 64);
        let caps = gst_video::VideoInfo::builder(gst_video::VideoFormat::Rgba, width, height)
            .build()
            .unwrap()
            .to_caps()
            .unwrap();
        let mut buffer = gst::Buffer::from_mut_slice(vec![200u8; (stride * height) as usize]);
        gst_video::VideoMeta::add_full(
            buffer.get_mut().unwrap(),
            gst_video::VideoFrameFlags::empty(),
            gst_video::VideoFormat::Rgba,
            width,
            height,
            &[0],
            &[stride as i32],
        )
        .unwrap();
        let slot = Arc::new(FrameSlot::default());
        slot.store(gst::Sample::builder().buffer(&buffer).caps(&caps).build());
        let primitive = VideoPrimitive {
            frame: slot.latest(),
        };
        let bounds = Rectangle::new(iced::Point::ORIGIN, iced::Size::new(800.0, 800.0));
        let viewport = Viewport::with_physical_size(iced::Size::new(800, 800), 1.0);
        primitive.prepare(&mut pipeline, &device, &queue, &bounds, &viewport);
        let size = pipeline.texture.as_ref().map(|t| (t.width, t.height));
        assert_eq!(size, Some((width, height)));
        let target = device.create_texture(&wgpu::TextureDescriptor {
            label: None,
            size: wgpu::Extent3d {
                width: 800,
                height: 800,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            view_formats: &[],
        });
        let view = target.create_view(&wgpu::TextureViewDescriptor::default());
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor::default());
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: None,
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });
            assert!(primitive.draw(&pipeline, &mut pass));
        }
        queue.submit([encoder.finish()]);
        assert!(device.pop_error_scope().await.is_none());
    }
}
