/**
 * Opt-in support for parallel bean instantiation during application context
 * bootstrap. Builds an approximate dependency graph of the singleton beans,
 * identifies independent chunks that can be created concurrently, and drives the
 * existing background-initialization machinery of the bean factory through a
 * bounded, CPU-sized thread pool.
 */
@NullMarked
package io.github.jdubois.springbooster;

import org.jspecify.annotations.NullMarked;
