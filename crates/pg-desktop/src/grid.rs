//! The timeline's layout: a heading per month, then justified rows planned
//! by the shared Rust planner. Only the rows near the viewport are built
//! into widgets; everything here is plain geometry so it can be tested.
use chrono::{Datelike, Local, TimeZone};
use std::ops::Range;

/// Space between tiles and between rows, in logical pixels.
pub const GAP: u32 = 4;
/// Height of a month heading.
pub const HEADING: f32 = 48.0;
/// Rows built above and below the viewport.
pub const OVERSCAN: f32 = 800.0;

#[derive(Debug, Clone, PartialEq)]
pub enum RowKind {
    Heading(String),
    /// Items `range` of the outline, in one row.
    Tiles(Range<usize>),
}

#[derive(Debug, Clone, PartialEq)]
pub struct Row {
    pub y: f32,
    pub height: f32,
    pub kind: RowKind,
}

#[derive(Debug, Clone, Default)]
pub struct Layout {
    pub rows: Vec<Row>,
    /// Each item's tile width, in outline order.
    pub widths: Vec<u32>,
    /// The row of each item.
    item_rows: Vec<u32>,
    pub height: f32,
    pub width: u32,
}

/// Month (as `year * 12 + month0`) of a capture time, in local time.
fn month_of(time: i64) -> i32 {
    match Local.timestamp_opt(time, 0).single() {
        Some(date) => date.year() * 12 + date.month0() as i32,
        None => i32::MIN,
    }
}

fn month_label(time: i64) -> String {
    match Local.timestamp_opt(time, 0).single() {
        Some(date) => date.format("%B %Y").to_string(),
        None => "Unknown date".into(),
    }
}

/// Where each month starts, with its heading.
pub fn sections(times: &[i64]) -> Vec<(usize, String)> {
    let mut out = Vec::new();
    let mut last = None;
    for (index, &time) in times.iter().enumerate() {
        let month = month_of(time);
        if last != Some(month) {
            out.push((index, month_label(time)));
            last = Some(month);
        }
    }
    out
}

impl Layout {
    /// Plan every row for a viewport `width` wide with rows near `target` high.
    pub fn plan(ratios: &[f32], times: &[i64], width: u32, target: u32) -> Self {
        let width = width.clamp(1, 32_768);
        let sections = sections(times);
        let starts: Vec<u32> = sections.iter().map(|(start, _)| *start as u32).collect();
        let ratios: Vec<f64> = ratios.iter().map(|&r| f64::from(r)).collect();
        let Ok(grid) = pg_core::grid::plan_sections(&ratios, &starts, width, target, GAP) else {
            return Self {
                width,
                ..Self::default()
            };
        };
        let mut layout = Self {
            rows: Vec::with_capacity(grid.row_starts.len() + sections.len()),
            item_rows: vec![0; ratios.len()],
            widths: grid.widths,
            height: 0.0,
            width,
        };
        let mut headings = sections.into_iter().peekable();
        let mut y = 0.0;
        let count = grid.row_starts.len();
        for row in 0..count {
            let start = grid.row_starts[row] as usize;
            let end = grid
                .row_starts
                .get(row + 1)
                .map_or(ratios.len(), |&s| s as usize);
            if let Some((_, label)) = headings.next_if(|(at, _)| *at == start) {
                layout.rows.push(Row {
                    y,
                    height: HEADING,
                    kind: RowKind::Heading(label),
                });
                y += HEADING;
            }
            let height = grid.row_heights[row] as f32;
            let index = layout.rows.len() as u32;
            layout.item_rows[start..end].fill(index);
            layout.rows.push(Row {
                y,
                height,
                kind: RowKind::Tiles(start..end),
            });
            y += height + GAP as f32;
        }
        layout.height = y;
        layout
    }

    /// The rows that meet `[top - OVERSCAN, top + height + OVERSCAN]`.
    pub fn visible(&self, top: f32, height: f32) -> Range<usize> {
        let from = top - OVERSCAN;
        let to = top + height + OVERSCAN;
        let first = self.rows.partition_point(|row| row.y + row.height < from);
        let last = self.rows.partition_point(|row| row.y <= to);
        first..last.max(first)
    }

    /// The items in `rows`.
    pub fn items_in(&self, rows: Range<usize>) -> Range<usize> {
        let mut items = rows.filter_map(|row| match &self.rows[row].kind {
            RowKind::Tiles(range) => Some(range.clone()),
            RowKind::Heading(_) => None,
        });
        let Some(first) = items.next() else {
            return 0..0;
        };
        let last = items.next_back().unwrap_or_else(|| first.clone());
        first.start..last.end
    }

    /// The top of the row holding `item`.
    pub fn item_y(&self, item: usize) -> Option<f32> {
        Some(self.item_bounds(item)?.0)
    }

    /// The top and height of the row holding `item`.
    pub fn item_bounds(&self, item: usize) -> Option<(f32, f32)> {
        let row = &self.rows[*self.item_rows.get(item)? as usize];
        Some((row.y, row.height))
    }

    /// The first item whose row reaches below `top`, and how far its row's
    /// top is from `top`: what to keep in place when the layout changes.
    pub fn anchor(&self, top: f32) -> Option<(usize, f32)> {
        let first = self.rows.partition_point(|row| row.y + row.height < top);
        self.rows[first..].iter().find_map(|row| match &row.kind {
            RowKind::Tiles(range) => Some((range.start, row.y - top)),
            RowKind::Heading(_) => None,
        })
    }

    /// The scroll offset that puts `anchor` back where it was.
    pub fn restore(&self, anchor: (usize, f32)) -> Option<f32> {
        let (item, offset) = anchor;
        Some((self.item_y(item)? - offset).max(0.0))
    }

    /// The item in the row above or below `item` (`rows` = -1 or 1) whose
    /// tile is nearest horizontally, for arrow keys.
    pub fn vertical_neighbor(&self, item: usize, rows: i32) -> Option<usize> {
        let row = *self.item_rows.get(item)? as usize;
        let x = self.center_x(item)?;
        let mut target = row as i64;
        loop {
            target += i64::from(rows.signum());
            let candidate = self.rows.get(usize::try_from(target).ok()?)?;
            if let RowKind::Tiles(range) = &candidate.kind {
                return range
                    .clone()
                    .min_by_key(|&other| (self.center_x(other).unwrap_or(0.0) - x).abs() as u32);
            }
        }
    }

    fn center_x(&self, item: usize) -> Option<f32> {
        let row = *self.item_rows.get(item)? as usize;
        let RowKind::Tiles(range) = &self.rows[row].kind else {
            return None;
        };
        let before: u32 = self.widths[range.start..item].iter().map(|w| w + GAP).sum();
        Some(before as f32 + self.widths[item] as f32 / 2.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i64 = 86_400;

    fn library(count: usize) -> (Vec<f32>, Vec<i64>) {
        let ratios = (0..count).map(|i| [1.5, 0.75, 1.0, 1.78][i % 4]).collect();
        // Newest first, a few photos a day.
        let times = (0..count)
            .map(|i| 1_790_000_000 - (i as i64 / 3) * DAY)
            .collect();
        (ratios, times)
    }

    #[test]
    fn every_item_lands_in_exactly_one_row() {
        let (ratios, times) = library(500);
        let layout = Layout::plan(&ratios, &times, 1200, 200);
        let mut covered = 0;
        for row in &layout.rows {
            if let RowKind::Tiles(range) = &row.kind {
                assert_eq!(range.start, covered);
                covered = range.end;
            }
        }
        assert_eq!(covered, 500);
        assert!(
            layout
                .rows
                .iter()
                .any(|r| matches!(r.kind, RowKind::Heading(_)))
        );
        assert!(layout.rows.windows(2).all(|pair| pair[0].y < pair[1].y));
    }

    #[test]
    fn the_visible_rows_do_not_grow_with_the_library() {
        let (small_r, small_t) = library(2_000);
        let (large_r, large_t) = library(100_000);
        let small = Layout::plan(&small_r, &small_t, 1400, 200);
        let large = Layout::plan(&large_r, &large_t, 1400, 200);
        let near_top = |layout: &Layout| layout.visible(3_000.0, 900.0).len();
        assert!(near_top(&large) <= near_top(&small) + 2);
        let deep = large.visible(large.height / 2.0, 900.0);
        assert!(deep.len() < 40, "{} rows built", deep.len());
        assert!(large.items_in(deep).len() < 300);
    }

    #[test]
    fn a_new_width_keeps_the_anchor_item_in_place() {
        let (ratios, times) = library(3_000);
        let wide = Layout::plan(&ratios, &times, 1600, 200);
        let top = 20_000.0;
        let anchor = wide.anchor(top).unwrap();
        let narrow = Layout::plan(&ratios, &times, 700, 200);
        let restored = narrow.restore(anchor).unwrap();
        // The anchor item's row sits where its old row did; at a new width
        // other items may now share that row, before or after it.
        let (y, _) = narrow.item_bounds(anchor.0).unwrap();
        assert!((y - anchor.1 - restored).abs() < 0.5);
        let (first, _) = narrow.anchor(restored).unwrap();
        assert_eq!(narrow.item_y(first), Some(y));
    }

    #[test]
    fn arrow_keys_move_to_the_nearest_tile_in_the_next_row() {
        let (ratios, times) = library(60);
        let layout = Layout::plan(&ratios, &times, 1000, 200);
        let below = layout.vertical_neighbor(0, 1).unwrap();
        assert!(below > 0);
        assert_eq!(
            layout.vertical_neighbor(below, -1).map(|i| i < below),
            Some(true)
        );
        assert_eq!(layout.vertical_neighbor(0, -1), None);
    }
}
