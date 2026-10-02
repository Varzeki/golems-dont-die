/**
 * Obstacle data: what the plugin keeps on disk about obstacles, and - in a later update - what it
 * will offer to send. <b>This package is the whole of it.</b>
 *
 * <h2>For a reviewer</h2>
 *
 * <p>Everything that decides what is recorded, how it is written and (later) what leaves the
 * machine is in these files, and they depend on nothing else in the plugin - only the JDK and a
 * logger. The rest of the plugin reaches this package through exactly one class,
 * {@code com.golemsdontdie.ObstacleDataBridge}, which copies numbers out of the plugin's own objects
 * into the plain records here. Reading this package and that bridge is reading all of it.
 *
 * <ul>
 *   <li>{@link com.golemsdontdie.telemetry.PlayerCrossing} - one crossing of an obstacle by the
 *       player: the object, what it played, how long it took, and the path taken across it.</li>
 *   <li>{@link com.golemsdontdie.telemetry.GolemCrossing} - one crossing of an obstacle by a golem
 *       the player could see: the route it used and the path it actually took.</li>
 *   <li>{@link com.golemsdontdie.telemetry.ObstacleDataFile} - the file: reading it, adding to it,
 *       and writing it back. The format is described at the top of that class and at the top of the
 *       file itself.</li>
 * </ul>
 *
 * <h2>Why it exists</h2>
 *
 * <p>Golems only use obstacles whose animation and movement are known, and the only way to know
 * one is to watch a player use it. There are hundreds in the game and no one player visits them
 * all, so what one player's crossing proves could unlock that obstacle for everybody in a later
 * release. The golem crossings are the other half: they show where golems perform an obstacle
 * wrongly - crossing a stile on the slant, landing short, hopping over a stepping stone - so it can
 * be fixed.
 *
 * <h2>What is kept, and what is not</h2>
 *
 * <p>Facts about the game world, never about the player. An object's id, its name and menu text as
 * the game cache has them, the animations it played, how long it took, the two tiles it joins, and
 * a path across it measured relative to those two tiles. There is no account or display name, no
 * world number, no session or install identifier, no date or time, and no position other than an
 * obstacle's own tiles. Two players crossing the same stile the same way write identical lines.
 *
 * <p>The file is bounded: one line per distinct crossing, repeats counted rather than added, and the
 * least recently seen dropped past a fixed number of lines.
 *
 * <h2>What leaves the machine</h2>
 *
 * <p>Nothing, unless the player turns on <i>Share obstacle data</i>, which is off by default and
 * warns before it turns on. The file is kept either way; the setting is the player's permission to
 * send it. With it on, {@link com.golemsdontdie.telemetry.ObstacleDataSender} sends whatever in the
 * file has not been sent yet, including what was kept before it was turned on, to
 * {@link com.golemsdontdie.telemetry.ObstacleDataSender#URL}: the file's own lines, each with how
 * many new sightings it carries, and the plugin's version. A batch at a time, a minute apart while
 * there is a backlog and then at most every fifteen minutes, and once more as the plugin stops.
 * Nothing else is sent, and this package is the only network code in the plugin.
 */
package com.golemsdontdie.telemetry;
