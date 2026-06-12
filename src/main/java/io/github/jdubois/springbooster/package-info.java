/**
 * Opt-in support for parallel bean instantiation during application context
 * bootstrap. Builds an approximate dependency graph of the singleton beans,
 * identifies independent chunks that can be created concurrently, and drives the
 * existing background-initialization machinery of the bean factory through a
 * bounded thread pool sized, by default, at twice the number of available
 * processors.
 */
@NullMarked
package io.github.jdubois.springbooster;

import org.jspecify.annotations.NullMarked;
