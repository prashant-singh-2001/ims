package com.furnitureims.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Downscales a photo so its long edge is at most {@link #MAX_LONG_EDGE_PX} pixels
 * (FR-ITEM-02), preserving aspect ratio; images already within the limit pass through
 * unchanged. Plain {@code java.desktop} (AWT/ImageIO) - no extra Maven dependency needed,
 * and no javafx-swing bridge required either.
 */
public final class ImageResizer {

    private static final int MAX_LONG_EDGE_PX = 1600;

    private ImageResizer() {
    }

    public static void resizeAndSave(Path sourceFile, Path targetFile) {
        try {
            BufferedImage original = ImageIO.read(sourceFile.toFile());
            if (original == null) {
                throw new IllegalArgumentException("Not a readable image: " + sourceFile);
            }
            BufferedImage output = scaleDownIfNeeded(original);
            String format = formatFor(targetFile);
            if (!ImageIO.write(output, format, targetFile.toFile())) {
                throw new IllegalStateException("No writer available for image format: " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to process image: " + sourceFile, e);
        }
    }

    private static BufferedImage scaleDownIfNeeded(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= MAX_LONG_EDGE_PX) {
            return original;
        }

        double scale = (double) MAX_LONG_EDGE_PX / longEdge;
        int newWidth = (int) Math.round(width * scale);
        int newHeight = (int) Math.round(height * scale);

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static String formatFor(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".png") ? "png" : "jpg";
    }
}
