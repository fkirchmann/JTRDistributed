/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.core.wordlist;

import java.io.*;

public interface WordlistGenerator {
    /**
     * Returns the number of words in the wordlist.
     */
    long getSize();

    /**
     * Outputs the words in the range [begin_incl, end_excl) to the given OutputStream. Each word most be encoded in
     * UTF-8, and followed by a newline ('\n').
     */
    void outputWords(long begin_incl, long end_excl, OutputStream os) throws IOException;

    /**
     * Returns the index of the given word in the wordlist, or throws an IllegalArgumentException if the word is not
     * in the wordlist.
     *
     * @return The index of the word in the wordlist. A call to outputWords(indexOf(word), indexOf(word)+1, os) must
     *        output the given word.
     */
    long indexOf(String word);
}
