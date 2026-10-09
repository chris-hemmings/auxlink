// SPDX-License-Identifier: MIT
//! Which GPIO carries which I2S signal, on both boards at once.
//!
//! XIAO RP2040 PORT. Pads wired source -> car: D0->D6, D1->D5, D2->D4, D3->D3.
//!
//! ```text
//!     signal    source (pad/GP)   car (pad/GP)
//!     lrck        D0 / 26          D6 / 0
//!     shield      D1 / 27          D5 / 7
//!     bck         D2 / 28          D4 / 6
//!     data        D3 / 29          D3 / 29
//! ```
//!
//! The source keeps LRCK/SHIELD/BCK consecutive (side-set needs that). On the
//! car side the pins are not consecutive, which `slave_rx` tolerates: it only
//! reads DATA from IN_BASE and reaches the clocks through `wait ... pin N`,
//! whose index is IN_BASE + N modulo 32 on RP2040. From IN_BASE = 29, GP6 is
//! index 9 and GP0 is index 3. SHIELD still sits physically between BCK and
//! LRCK on both boards (adjacent pads D0-D1-D2 and D4-D5-D6).
//!
//! The clock-locked car build (sink as I2S master, `master_rx`) needs BCK,
//! SHIELD, LRCK consecutive on the car board and is NOT possible with this
//! wiring; it fails to compile on purpose.
//!
//! Original RP2040-Zero notes follow; the numbers there no longer apply.
//!
//! The two RP2040-Zeros are soldered bottom edge to bottom edge with one
//! rotated 180 degrees, so their pads face each other in reverse order: GP14
//! meets GP8, GP13 meets GP9, and so on down the row. Every facing pair sums to
//! [`FACING_SUM`], which is the first thing the asserts at the bottom check.
//!
//! ```text
//!     signal    source   sink
//!     data        12      10
//!     bck         11      11
//!     shield      10      12
//!     lrck         9      13
//! ```
//!
//! Physical order along the joint is the same on both boards — data, bck,
//! shield, lrck — because the GPIO numbering runs one way on one board and the
//! other way on the other.
//!
//! # What SHIELD is for
//!
//! It carries nothing. The master board holds it low for the entire run so that
//! a driven conductor sits between BCK and LRCK.
//!
//! That is the whole point of this layout. An eleven-minute recording compared
//! against the file that was played showed 112 samples in the first two and a
//! half minutes whose right channel had been replaced by its own sign bit —
//! `(sample & 0xFFFF) >> 15`, 54 of 54 checked by hand, left channel never
//! touched, jumps up to 44% of full scale. That is what a right slot read
//! fifteen bit-clocks early looks like: fifteen bits of the left slot's zero
//! padding, then one bit of the real sample. It happens when `wait 1 pin` on
//! LRCK releases before the true edge, and BCK switching at 3 MHz on the
//! neighbouring wire is the obvious thing that would release it.
//!
//! # Why the order is not free
//!
//! Two hardware constraints, not preferences:
//!
//!  * The sink runs `i2s_pio::slave_rx`, whose `in pins, 1` samples from
//!    PINCTRL_IN_BASE and nowhere else. DATA must be the **lowest** of the
//!    sink's three pins, with BCK and LRCK at the fixed offsets below — those
//!    are the `wait ... pin N` indices in the program.
//!  * The source runs `i2s_pio::master_tx`, which drives LRCK, SHIELD and BCK
//!    from side-set, and side-set pins must be **consecutive and ascending**.
//!    This is also why SHIELD has to be a side-set bit rather than a plain
//!    output: a pin can only sit between BCK and LRCK if it takes the GPIO
//!    number between them, and then all three have to belong to the side-set.
//!
//! Editing this file moves the wiring. The asserts fail the build if an edit
//! breaks either constraint, rather than letting it through as a right channel
//! that is quietly wrong.
//!
//! # Two places, kept in step
//!
//! Each number appears twice: once as a constant the asserts check, and once
//! inside the macro that names the peripheral. embassy's pins are distinct
//! types and cannot be picked by value, so `p.PIN_10` has to be written out.
//! The macros assert against the constants, so changing one without the other
//! fails to compile and says so.

#![allow(dead_code)]

/// Offset from the sink's IN base at which `slave_rx` expects BCK.
/// Must match the `wait ... pin 9` indices in that program.
pub const SINK_BCK_OFFSET: u8 = 9;

/// Offset from the sink's IN base at which `slave_rx` expects LRCK.
/// Must match the `wait ... pin 3` indices in that program.
pub const SINK_LRCK_OFFSET: u8 = 3;

/// The phone-facing board: USB in, I2S out. Drives every clock, including the
/// shield. XIAO pads D3, D2, D1, D0.
pub mod source {
    pub const DATA: u8 = 29;
    pub const BCK: u8 = 28;
    pub const SHIELD: u8 = 27;
    pub const LRCK: u8 = 26;
}

/// The car-facing board: I2S in, USB out. XIAO pads D3, D4, D5, D6.
pub mod sink {
    pub const DATA: u8 = 29;
    pub const BCK: u8 = 6;
    pub const SHIELD: u8 = 7;
    pub const LRCK: u8 = 0;
}

macro_rules! sink_i2s_pins {
    ($p:expr) => {{
        const _: () = assert!(
            pins::sink::DATA == 29 && pins::sink::BCK == 6 && pins::sink::LRCK == 0,
            "pins::sink and sink_i2s_pins! disagree; update both",
        );
        ($p.PIN_29, $p.PIN_6, $p.PIN_0)
    }};
}

/// Only used by the clock-locked car build, which this wiring cannot support.
macro_rules! sink_shield_pin {
    ($p:expr) => {{
        compile_error!(
            "clock-locked car build needs BCK/SHIELD/LRCK consecutive on the car board; \
             impossible with the XIAO D0-D6/D1-D5/D2-D4/D3-D3 wiring"
        );
        $p.PIN_7
    }};
}

macro_rules! source_i2s_pins {
    ($p:expr) => {{
        const _: () = assert!(
            pins::source::DATA == 29
                && pins::source::BCK == 28
                && pins::source::SHIELD == 27
                && pins::source::LRCK == 26,
            "pins::source and source_i2s_pins! disagree; update both",
        );
        ($p.PIN_29, $p.PIN_28, $p.PIN_27, $p.PIN_26)
    }};
}

// ── master_tx: side-set is (LRCK, SHIELD, BCK) upward from the lowest pin ───
const _: () = assert!(
    source::SHIELD == source::LRCK + 1 && source::BCK == source::LRCK + 2,
    "source LRCK/SHIELD/BCK must be consecutive ascending: side-set cannot skip a pin",
);

// ── slave_rx: data at the IN base, clocks at the program's wait indices ─────
// `wait pin N` selects GPIO (IN_BASE + N) mod 32.
const _: () = assert!(
    (sink::DATA + SINK_BCK_OFFSET) % 32 == sink::BCK,
    "sink BCK must sit at SINK_BCK_OFFSET (mod 32) above DATA; see slave_rx",
);
const _: () = assert!(
    (sink::DATA + SINK_LRCK_OFFSET) % 32 == sink::LRCK,
    "sink LRCK must sit at SINK_LRCK_OFFSET (mod 32) above DATA; see slave_rx",
);
