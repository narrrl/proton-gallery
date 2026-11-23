//! Shared visual tokens for the native desktop surfaces.
use iced::widget::{button, container, svg, text, text_input};
use iced::{Border, Color, Font, Theme, color};
use std::sync::LazyLock;

pub const MEDIUM: Font = Font {
    weight: iced::font::Weight::Semibold,
    ..Font::with_name("Inter")
};
pub const INK: Color = color!(0x1B1340);
pub const VIOLET: Color = color!(0x6D4AFF);
pub const VIEWER: Color = color!(0x151519);

pub struct Colors {
    pub surface: Color,
    pub sidebar: Color,
    pub muted: Color,
    pub line: Color,
    pub selected: Color,
    pub accent: Color,
    pub placeholder: Color,
}

pub fn colors(theme: &Theme) -> Colors {
    if theme.extended_palette().is_dark {
        // The suite's plum-black (Proton Stream's `docs/DESIGN-TOKENS.md`).
        Colors {
            surface: color!(0x100E17),
            sidebar: color!(0x17141F),
            muted: color!(0xAAA4BD),
            line: color!(0x2E283D),
            selected: color!(0x372580),
            accent: color!(0xEFE9FF),
            placeholder: color!(0x1E1A29),
        }
    } else {
        Colors {
            surface: Color::WHITE,
            sidebar: color!(0xF7F5FC),
            muted: color!(0x615970),
            line: color!(0xE5E0EE),
            selected: color!(0xECE7FF),
            accent: color!(0x6440D9),
            placeholder: color!(0xF0EDF7),
        }
    }
}

pub fn theme(mode: iced::theme::Mode) -> Theme {
    static LIGHT: LazyLock<Theme> = LazyLock::new(|| {
        Theme::custom(
            "Gallery light",
            iced::theme::Palette {
                background: Color::WHITE,
                text: INK,
                primary: VIOLET,
                success: color!(0x247A61),
                warning: color!(0x946600),
                danger: color!(0xBA3450),
            },
        )
    });
    static DARK: LazyLock<Theme> = LazyLock::new(|| {
        Theme::custom(
            "Gallery dark",
            iced::theme::Palette {
                background: color!(0x100E17),
                text: color!(0xF4F2FA),
                primary: color!(0x8A6DFF),
                success: color!(0x6EC9A8),
                warning: color!(0xEAC078),
                danger: color!(0xFF93A8),
            },
        )
    });
    if mode == iced::theme::Mode::Dark {
        DARK.clone()
    } else {
        LIGHT.clone()
    }
}

pub fn muted(theme: &Theme) -> text::Style {
    text::Style {
        color: Some(colors(theme).muted),
    }
}

pub fn sidebar(theme: &Theme) -> container::Style {
    container::Style {
        background: Some(colors(theme).sidebar.into()),
        ..Default::default()
    }
}

pub fn panel(theme: &Theme) -> container::Style {
    let c = colors(theme);
    container::Style {
        background: Some(
            if theme.extended_palette().is_dark {
                color!(0x1C182B)
            } else {
                Color::WHITE
            }
            .into(),
        ),
        border: Border {
            color: c.line,
            width: 1.0,
            radius: 16.0.into(),
        },
        ..Default::default()
    }
}

pub fn soft(theme: &Theme) -> container::Style {
    container::Style {
        background: Some(colors(theme).sidebar.into()),
        border: Border {
            radius: 10.0.into(),
            ..Default::default()
        },
        ..Default::default()
    }
}

pub fn toolbar(theme: &Theme) -> container::Style {
    container::Style {
        background: Some(
            if theme.extended_palette().is_dark {
                color!(0x1C182B)
            } else {
                color!(0xFAF9FC)
            }
            .into(),
        ),
        ..Default::default()
    }
}

pub fn quiet(theme: &Theme, status: button::Status) -> button::Style {
    let mut style = secondary(theme, status);
    style.border.width = 0.0;
    if status == button::Status::Active || status == button::Status::Disabled {
        style.background = None;
    }
    style
}

pub fn secondary(theme: &Theme, status: button::Status) -> button::Style {
    let c = colors(theme);
    button::Style {
        background: Some(
            if status == button::Status::Hovered || status == button::Status::Pressed {
                c.selected
            } else {
                c.surface
            }
            .into(),
        ),
        text_color: if status == button::Status::Disabled {
            c.muted
        } else {
            theme.palette().text
        },
        border: Border {
            color: c.line,
            width: 1.0,
            radius: 8.0.into(),
        },
        ..Default::default()
    }
}

pub fn primary(theme: &Theme, status: button::Status) -> button::Style {
    let mut style = button::primary(theme, status);
    style.border.radius = 8.0.into();
    style
}

pub fn input(theme: &Theme, status: text_input::Status) -> text_input::Style {
    let mut style = text_input::default(theme, status);
    style.border = Border {
        color: if matches!(status, text_input::Status::Focused { .. }) {
            theme.palette().primary
        } else {
            colors(theme).line
        },
        width: 1.0,
        radius: 8.0.into(),
    };
    style
}

#[derive(Clone, Copy)]
pub enum Icon {
    Photos,
    Refresh,
    Back,
    Next,
    Shield,
    Exit,
    Image,
    Play,
    Heart,
    Albums,
    Folder,
    Lock,
    Add,
    Map,
    Fit,
}

// Small native SVGs share a 24px grid and 1.7px stroke; no icon font required.
pub fn icon<'a>(kind: Icon, size: f32) -> svg::Svg<'a> {
    let path = match kind {
        Icon::Photos => {
            "<rect x='3' y='3' width='18' height='18' rx='4'/><circle cx='8' cy='8' r='1.5'/><path d='m3 16 5-5 4 4 3-3 6 6'/>"
        }
        Icon::Refresh => "<path d='M20 8a8 8 0 1 0 0 8M20 3v5h-5'/>",
        Icon::Back => "<path d='m14 6-6 6 6 6'/>",
        Icon::Next => "<path d='m10 6 6 6-6 6'/>",
        Icon::Shield => "<path d='M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6Z'/><path d='m8 12 3 3 5-6'/>",
        Icon::Exit => "<path d='M9 4H4v16h5m4-12 4 4-4 4m-5-4h12'/>",
        Icon::Image => {
            "<rect x='3' y='4' width='18' height='16' rx='3'/><path d='m3 16 5-5 4 4 3-3 6 6'/><circle cx='15' cy='8' r='1'/>"
        }
        Icon::Heart => "<path d='M20 5c-3-3-6-1-8 1-2-2-5-4-8-1-4 4 1 9 8 15 7-6 12-11 8-15Z'/>",
        Icon::Albums => {
            "<rect x='6' y='7' width='15' height='14' rx='3'/><path d='M17 3H6a3 3 0 0 0-3 3v11'/>"
        }
        Icon::Play => "<path d='m9 5 10 7-10 7Z'/>",
        Icon::Lock => {
            "<rect x='5' y='10' width='14' height='11' rx='2'/><path d='M8 10V7a4 4 0 0 1 8 0v3m-4 4v3'/>"
        }
        Icon::Add => "<path d='M12 5v14M5 12h14'/>",
        Icon::Map => {
            "<path d='M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 0 1 16 0Z'/><circle cx='12' cy='10' r='2.5'/>"
        }
        Icon::Fit => "<path d='M4 9V4h5m6 0h5v5m0 6v5h-5m-6 0H4v-5'/>",
        Icon::Folder => {
            "<path d='M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z'/>"
        }
    };
    let source = format!(
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round'>{path}</svg>"
    );
    svg(svg::Handle::from_memory(source.into_bytes()))
        .width(size)
        .height(size)
        .style(move |theme: &Theme, _| svg::Style {
            color: Some(if matches!(kind, Icon::Add) {
                if theme.extended_palette().is_dark {
                    color!(0x28135F)
                } else {
                    Color::WHITE
                }
            } else {
                theme.palette().text
            }),
        })
}

pub fn logo(size: f32) -> iced::widget::Image {
    static HANDLE: LazyLock<iced::widget::image::Handle> = LazyLock::new(|| {
        iced::widget::image::Handle::from_bytes(
            include_bytes!("../../../images/icon-512.png").as_slice(),
        )
    });
    iced::widget::image(HANDLE.clone()).width(size).height(size)
}

pub fn navigation(theme: &Theme, _status: button::Status) -> button::Style {
    let c = colors(theme);
    button::Style {
        background: Some(c.selected.into()),
        text_color: c.accent,
        border: Border {
            radius: 9.0.into(),
            ..Default::default()
        },
        ..Default::default()
    }
}
