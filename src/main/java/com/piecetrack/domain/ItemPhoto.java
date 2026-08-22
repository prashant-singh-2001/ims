package com.piecetrack.domain;

/** relativePath is relative to {@code Photos/<itemModelId>/} - see AppPaths.photos(). */
public record ItemPhoto(long id, long itemModelId, String relativePath, int sortOrder, boolean primary) {
}
