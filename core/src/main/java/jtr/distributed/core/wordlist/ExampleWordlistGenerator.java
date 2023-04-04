/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.core.wordlist;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ExampleWordlistGenerator implements WordlistGenerator {
    private static final long START = 0;
    private static final long END = 1000000;
    @Override
    public long getSize() {
        return END - START;
    }

    @Override
    public void outputWords(long begin_incl, long end_excl, OutputStream os) throws IOException {
        if(begin_incl < 0) throw new IllegalArgumentException("Negative begin index");
        if(begin_incl > end_excl) throw new IllegalArgumentException("begin must be smaller than end");
        if(end_excl > getSize()) throw new IllegalArgumentException("End exceeds size");

        for(long i = begin_incl; i < end_excl; i++) {
            os.write(Long.toString(i).getBytes(StandardCharsets.UTF_8));
            os.write('\n');
        }
    }

    @Override
    public long indexOf(String word) {
        return Long.parseLong(word) + START;
    }
}
