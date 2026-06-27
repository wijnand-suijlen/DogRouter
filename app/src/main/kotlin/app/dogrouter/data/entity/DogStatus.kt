package app.dogrouter.data.entity

/**
 * What a dog needs from the walker on a day. Replaces the old `active` boolean:
 * the three `BOARD_*` values are the sleepover ("oppashond") day positions, each
 * fixing where the dog's day starts and ends (the start/end anchors — see
 * `DogStatus.anchors()` in the domain layer and docs/SLEEPOVER_DESIGN.md).
 *
 * Set by hand per dog on the Dogs list (no calendar for now); a multi-day stay
 * is BOARD_ARRIVE on day 1, BOARD_STAY on the middle days, BOARD_LEAVE on the
 * last. [isBoarding] dogs are scheduled as all-day passengers, not from their
 * fixed-window schedule rules.
 *
 * This enum stays in the data layer and carries no domain types (the anchor
 * mapping lives in the domain layer, the allowed dependency direction).
 */
enum class DogStatus(val isBoarding: Boolean) {
    /** Ignored entirely: no walks, no boarding (the old `active = false`). */
    OFF(false),

    /** Normal: walked from its schedule rules as usual. */
    WALK(false),

    /** Boarding, first day: collected at the dog's own address, ends at the
     *  walker's home (anchor OWNER_HOME → WALKER_HOME). */
    BOARD_ARRIVE(true),

    /** Boarding, middle day: starts and ends at the walker's home
     *  (anchor WALKER_HOME → WALKER_HOME). */
    BOARD_STAY(true),

    /** Boarding, last day: starts at the walker's home, returned to the dog's
     *  own address (anchor WALKER_HOME → OWNER_HOME). */
    BOARD_LEAVE(true),
}
