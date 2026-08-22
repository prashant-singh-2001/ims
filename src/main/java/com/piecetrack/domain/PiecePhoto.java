package com.piecetrack.domain;

/** relativePath is relative to {@code Photos/pieces/<pieceId>/} - see AppPaths.photos(). */
public record PiecePhoto(long id, long pieceId, String relativePath, int sortOrder, boolean primary) {
}
