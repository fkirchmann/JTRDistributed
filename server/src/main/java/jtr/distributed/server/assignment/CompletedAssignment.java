/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.assignment;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.SneakyThrows;

@Getter
public class CompletedAssignment extends Assignment {
    public CompletedAssignment(@JsonProperty("beginIndex") long beginIndex, @JsonProperty("endIndex") long endIndex) {
        super(beginIndex, endIndex);
    }

    @Override
    @SneakyThrows
    public CompletedAssignment clone() {
        return new CompletedAssignment(getBeginIndex(), getEndIndex());
    }
}
