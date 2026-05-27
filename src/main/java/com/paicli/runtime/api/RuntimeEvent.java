package com.paicli.runtime.api;

import java.time.Instant;

public record RuntimeEvent(long id, String threadId, String type, String data, Instant createdAt) {
}
