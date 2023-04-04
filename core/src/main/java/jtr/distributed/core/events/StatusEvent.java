/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.core.events;

import lombok.*;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusEvent extends JTEvent {
    long guessesPerSecond, beginIndex, lastGuessedIndex, endIndex;
    String lastGuessedPassword, gpuModel;
    Instant timestamp = Instant.now();
}
