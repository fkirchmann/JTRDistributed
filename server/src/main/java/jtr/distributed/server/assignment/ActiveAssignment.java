/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.assignment;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;

@Getter
public class ActiveAssignment extends Assignment {
    private final String clientId;

    public ActiveAssignment(@NonNull @JsonProperty("clientId") String clientId,
                            @JsonProperty("beginIndex") long beginIndex, @JsonProperty("endIndex") long endIndex) {
        super(beginIndex, endIndex);
        this.clientId = clientId;
    }

    @Override
    @SneakyThrows
    public ActiveAssignment clone() {
        return new ActiveAssignment(getClientId(), getBeginIndex(), getEndIndex());
    }

    @Override
    public String toString() {
        return "[ " + clientId + ": " + super.toString();
    }
}
