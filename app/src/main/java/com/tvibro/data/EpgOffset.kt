package com.tvibro.data

/**
 * A shift of the user's own clock on top of the times the EPG source reported.
 *
 * The rows in the database keep the times the source gave, so moving the setting costs nothing and
 * needs no reimport: the queries walk the stored scale, and everything handed back out is moved
 * onto the wall clock the user expects. A positive offset means the guide reads later than the
 * source says, which is the direction a provider ahead of the local clock needs.
 */
internal object EpgOffset {

    /** The offset in milliseconds, from the minutes the setting holds. */
    fun ms(minutes: Int): Long = minutes * 60_000L

    /**
     * An instant on the wall clock as the scale the rows are stored on, for use in a WHERE clause.
     * A row that should read as 20:00 is stored at 20:00 minus the offset, so a window asked for in
     * wall-clock terms has to be walked on the same scale or the ends of it miss their rows.
     */
    fun toStored(real: Long, offsetMs: Long): Long = real - offsetMs

    /** A stored instant back onto the wall clock, applied to a row as it is read out. */
    fun toReal(stored: Long, offsetMs: Long): Long = stored + offsetMs
}