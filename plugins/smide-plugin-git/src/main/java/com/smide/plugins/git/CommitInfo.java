package com.smide.plugins.git;

import java.time.Instant;

/**
 * A commit as the Log tab shows it.
 *
 * @param id           the full SHA-1
 * @param shortId      the first seven characters
 * @param shortMessage the first line of the message
 * @param fullMessage  the whole message
 * @param author       the author's name
 * @param authorEmail  the author's e-mail address
 * @param date         the author date
 */
public record CommitInfo(String id,
                         String shortId,
                         String shortMessage,
                         String fullMessage,
                         String author,
                         String authorEmail,
                         Instant date) {
}
