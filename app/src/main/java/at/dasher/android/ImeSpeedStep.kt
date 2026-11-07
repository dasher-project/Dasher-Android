package at.dasher.android

/**
 * Android #63: the IME stepper's step size — pure so the formula is pinned
 * (review I5). One nudge = 1/40th of the engine-declared range, at least 10
 * (the historic default range 20..400 keeps its familiar step of 10; the
 * manifest's wider ~1..625 range steps 15).
 */
fun speedStepFor(range: IntRange): Int =
    ((range.last - range.first) / 40).coerceAtLeast(10)
