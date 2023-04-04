/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.assignment;

import lombok.*;

import java.util.Locale;
import java.util.TreeSet;

@Getter
@Setter(AccessLevel.PROTECTED)
public abstract class Assignment implements Comparable<Assignment>, Cloneable {
    private long beginIndex, endIndex;

    public Assignment(long beginIndex, long endIndex) {
        if (beginIndex > endIndex) {
            throw new IllegalArgumentException("Begin index " + beginIndex
                    + " is before end index " + endIndex);
        }
        this.beginIndex = beginIndex;
        this.endIndex = endIndex;
    }

    public boolean overlaps(@NonNull Assignment other) {
        // equal to !isBefore(o) && !isAfter(o)
        return this.beginIndex < other.endIndex && this.endIndex > other.beginIndex;
    }

    public boolean overlapsOrBorders(@NonNull Assignment other) {
        return this.beginIndex <= other.endIndex && this.endIndex >= other.beginIndex;
    }

    public boolean isBeforeOrBordersBefore(@NonNull Assignment other) {
        return this.endIndex <= other.beginIndex;
    }

    public boolean isAfterOrBordersAfter(@NonNull Assignment other) {
        return this.beginIndex >= other.endIndex;
    }

    public boolean isBefore(@NonNull Assignment other) {
        return this.endIndex < other.beginIndex;
    }

    public boolean isAfter(@NonNull Assignment other) {
        return this.beginIndex > other.endIndex;
    }

    public long size() {
        return endIndex - beginIndex;
    }

    @SneakyThrows
    public <T extends Assignment> TreeSet<T> computeOverlapOrBorder(TreeSet<T> set) {
        TreeSet<T> result = new TreeSet<>();
        for (T assignment : set) {
            if (this.overlapsOrBorders(assignment)) {
                result.add(assignment);
            } else if (assignment.getBeginIndex() > this.getEndIndex()) {
                break;
            }
        }
        return result;
    }

    @SneakyThrows
    public <T extends Assignment> TreeSet<T> computeOverlap(TreeSet<T> set) {
        TreeSet<T> result = new TreeSet<>();
        for (T assignment : set) {
            if (this.overlaps(assignment)) {
                result.add(assignment);
            } else if (assignment.getBeginIndex() >= this.getEndIndex()) {
                break;
            }
        }
        return result;
    }

    @Override
    public int compareTo(Assignment o) {
        return beginIndex != o.beginIndex ?
                Long.compare(beginIndex, o.beginIndex)
                : Long.compare(endIndex, o.endIndex);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Assignment
                && ((Assignment) other).getBeginIndex() == this.getBeginIndex()
                && ((Assignment) other).getEndIndex() == this.getEndIndex();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(getBeginIndex()) ^ Long.hashCode(getEndIndex());
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "[%,d; %,d)", beginIndex, endIndex).replace(',', '.');
    }
}
