/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.assignment;

import com.esotericsoftware.minlog.Log;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.*;

@Getter
public class AssignmentManager implements Cloneable {
    private final long size;
    private final TreeSet<CompletedAssignment> completedAssignments;
    private final TreeSet<ActiveAssignment> activeAssignments;
    private final Map<String, ActiveAssignment> activeAssignmentsByClientId;

    public AssignmentManager(long size) {
        this.size = size;
        this.completedAssignments = new TreeSet<>();
        this.activeAssignments = new TreeSet<>();
        this.activeAssignmentsByClientId = new HashMap<>();
    }

    public AssignmentManager(@JsonProperty("size") long size,
                             @JsonProperty("completedAssignments") TreeSet<CompletedAssignment> completedAssignments,
                             @JsonProperty("activeAssignments") TreeSet<ActiveAssignment> activeAssignments,
                             @JsonProperty("activeAssignmentsByClientId") Map<String, ActiveAssignment> activeAssignmentsByClientId) {
        this.size = size;
        this.completedAssignments = completedAssignments;
        this.activeAssignments = activeAssignments;
        this.activeAssignmentsByClientId = activeAssignmentsByClientId;
    }

    public void markCompleted(long beginIndex, long endIndex) {
        CompletedAssignment assignment = new CompletedAssignment(beginIndex, endIndex);
        Log.trace("Marking as completed: " + assignment);
        for(CompletedAssignment other : assignment.computeOverlapOrBorder(completedAssignments)) {
            assignment.setBeginIndex(Math.min(assignment.getBeginIndex(), other.getBeginIndex()));
            assignment.setEndIndex(Math.max(assignment.getEndIndex(), other.getEndIndex()));
            Log.trace(" -> Expanded to: " + assignment + " due to other assignment " + other);
            completedAssignments.remove(other);
        }
        completedAssignments.add(assignment);
    }

    public ActiveAssignment getAssignment(String clientId) {
        return activeAssignmentsByClientId.get(clientId);
    }

    private void putAssignment(@NonNull ActiveAssignment assignment) {
        ActiveAssignment old = activeAssignmentsByClientId.get(assignment.getClientId());
        if(old != null) {
            Log.warn(assignment.getClientId(), "Overwritten old assignment " + old);
            removeAssignment(assignment.getClientId());
        }
        TreeSet<ActiveAssignment> overlappingSet = assignment.computeOverlap(activeAssignments);
        if(!overlappingSet.isEmpty()) {
            Log.warn(assignment.getClientId(), "New Assignment " + assignment + " overlaps with other assignments:");
            for(ActiveAssignment other : overlappingSet) {
                Log.warn(assignment.getClientId(), "  Overlap:" + other);
            }
        }
        activeAssignments.add(assignment);
        activeAssignmentsByClientId.put(assignment.getClientId(), assignment);
    }

    public boolean removeAssignment(@NonNull String clientId) {
        ActiveAssignment old = activeAssignmentsByClientId.get(clientId);
        if(old != null) {
            activeAssignments.remove(old);
            activeAssignmentsByClientId.remove(clientId);
            return true;
        }
        return false;
    }

    public ActiveAssignment getOrCreateAssignment(@NonNull String clientId, long maxSize) {
        if(maxSize > size) { throw new IllegalArgumentException("target size exceeds wordlist size"); }
        if(maxSize <= 0) { throw new IllegalArgumentException("Invalid target size " + maxSize); }

        ActiveAssignment assignment = getAssignment(clientId);
        if(assignment != null) {
            TreeSet<CompletedAssignment> overlap = assignment.computeOverlap(completedAssignments);
            // If the client's assignment is actually already partially solved, remove it
            // and create a new assignment
            if(!overlap.isEmpty()) {
                removeAssignment(assignment.getClientId());
            } else {
                return assignment;
            }
        }

        long lastAssignmentEnd = 0;
        TreeSet<Assignment> allAssignments = new TreeSet<>();
        allAssignments.addAll(activeAssignments);
        allAssignments.addAll(completedAssignments);
        for(Assignment other : allAssignments) {
            Log.trace(" - Assignment search: check: " + other, ", last: "+ lastAssignmentEnd);
            if(lastAssignmentEnd < other.getBeginIndex()) {
                // Found a free spot!
                assignment =  new ActiveAssignment(clientId, lastAssignmentEnd,
                        Math.min(other.getBeginIndex(), lastAssignmentEnd + maxSize));
                break;
            }
            lastAssignmentEnd = Math.max(other.getEndIndex(), lastAssignmentEnd);
        }
        if(assignment == null) {
            if(allAssignments.isEmpty() || lastAssignmentEnd < size) {
                assignment = new ActiveAssignment(clientId, lastAssignmentEnd,
                        Math.min(size, lastAssignmentEnd + maxSize));
            } else {
                Log.warn(clientId, "Could not create assignment: no work left!");
                return null;
            }
        }
        if(assignment.size() == 0) {
            Log.warn(clientId, "Created empty assignment " + assignment);
            return null;
        }
        putAssignment(assignment);
        return assignment.size() > 0 ? assignment : null;
    }

    public double getAssignmentCompletion(ActiveAssignment assignment) {
        long completed = 0;
        for(CompletedAssignment overlap : assignment.computeOverlap(completedAssignments)) {
            long overlapBegin = Math.max(assignment.getBeginIndex(), overlap.getBeginIndex());
            long overlapEnd = Math.min(assignment.getEndIndex(), overlap.getEndIndex());
            if(overlapBegin < overlapEnd) {
                completed += overlapEnd - overlapBegin;
            }
        }
        return ((double) completed) / ((double) assignment.size());
    }

    @Override
    public AssignmentManager clone() {
        AssignmentManager clone = new AssignmentManager(size);
        for(ActiveAssignment assignment : activeAssignments) {
            ActiveAssignment assignmentClone = assignment.clone();
            clone.activeAssignments.add(assignmentClone);
            clone.activeAssignmentsByClientId.put(assignment.getClientId(), assignmentClone);
        }
        for(CompletedAssignment assignment : completedAssignments) {
            CompletedAssignment assignmentClone = assignment.clone();
            clone.completedAssignments.add(assignmentClone);
        }
        return clone;
    }

    public void sanityCheckAssignments() {
        sanityCheckAssignments(activeAssignments);
        sanityCheckAssignments(completedAssignments);
    }

    private void sanityCheckAssignments(TreeSet<? extends Assignment> set) {
        Assignment previous = null;
        for(Assignment current : set) {
            if(previous != null && previous.getEndIndex() > current.getBeginIndex()) {
                Log.warn("", new IllegalStateException("Assignment " + current + " overlaps with " + previous));
            }
            if(current.getBeginIndex() < 0 || current.getEndIndex() > size) {
                Log.warn("", new IllegalStateException("Assignment " + current + " out of bounds!"));
            }
            if(current.getBeginIndex() > current.getEndIndex()) {
                Log.warn("", new IllegalStateException("Assignment " + current + " endIndex is before beginIndex"));
            }
            if(current.size() <= 0) {
                Log.warn("", new IllegalStateException("Assignment " + current + " has invalid size " + current.size()));
            }
            previous = current;
        }
    }

}
