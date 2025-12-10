package io.yamsergey.adt.tools.android.inspect.scroll;

import io.yamsergey.adt.tools.android.inspect.ViewHierarchy;
import io.yamsergey.adt.tools.android.inspect.ViewHierarchyDumper;
import io.yamsergey.adt.tools.android.inspect.ViewHierarchyParser;
import io.yamsergey.adt.tools.android.inspect.ViewNode;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import lombok.Builder;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Captures scrolling screenshots using a fixed-geometry algorithm inspired by PGSSoft's approach.
 *
 * <p>This approach uses predictable geometry for seamless stitching:</p>
 * <ul>
 *   <li>Scroll exactly 50% of scrollable area height</li>
 *   <li>Skip exactly 25% from top of each subsequent screenshot (the overlap)</li>
 *   <li>Stack segments directly - no hash detection needed</li>
 * </ul>
 *
 * <p>The math ensures perfect alignment:</p>
 * <ul>
 *   <li>After scrolling 50%, content at position P moves to P - height/2</li>
 *   <li>The top 25% of new screenshot = bottom 25% of previous screenshot</li>
 *   <li>By skipping this overlap, segments align perfectly</li>
 * </ul>
 *
 * <p>Capture strategy:</p>
 * <ul>
 *   <li>First screenshot: full screenshot (includes static top bar)</li>
 *   <li>Subsequent screenshots: skip top 25% of scrollable area, use rest</li>
 *   <li>Last screenshot: extend to screen bottom (includes static bottom nav)</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * Result&lt;ScrollScreenshot&gt; result = FixedStepScrollCapture.builder()
 *     .outputFile(new File("long-screenshot.png"))
 *     .scrollToTop(true)
 *     .build()
 *     .capture();
 * </pre>
 */
@Builder
public class FixedStepScrollCapture {

    // Device settings
    private final String deviceSerial;
    private final String adbPath;

    @Builder.Default
    private final int timeoutSeconds = 30;

    // Output settings
    private final File outputFile;

    // Scroll behavior
    @Builder.Default
    private final int scrollDelayMs = 600;

    @Builder.Default
    private final int maxCaptures = 30;

    @Builder.Default
    private final boolean scrollToTop = false;

    @Builder.Default
    private final int scrollToTopSwipes = 10;

    /**
     * Scroll step in pixels. Only used if set explicitly.
     * By default, scrolls 50% of scrollable area height for optimal stitching.
     */
    private final Integer scrollStep;

    /**
     * Swipe duration in milliseconds. Longer = slower, more predictable scroll.
     */
    @Builder.Default
    private final int swipeDurationMs = 500;

    // View targeting
    private final String targetViewId;

    // Debug mode
    @Builder.Default
    private final boolean debugMode = false;

    // Internal components
    private final ScrollableViewFinder viewFinder = new ScrollableViewFinder();
    private final RowHashCalculator hashCalculator = new RowHashCalculator();

    /**
     * Captures a scrolling screenshot using the fixed-geometry algorithm.
     *
     * @return Result containing ScrollScreenshot on success, or Failure with error description
     */
    public Result<ScrollScreenshot> capture() {
        try {
            if (outputFile == null) {
                return new Failure<>(null, "Output file is required");
            }

            String adbCommand = adbPath != null ? adbPath : "adb";

            // Verify ADB and device
            Result<Void> adbCheck = verifyAdb(adbCommand);
            if (adbCheck instanceof Failure<Void> failure) {
                return new Failure<>(null, failure.description());
            }

            List<String> adbPrefix = buildAdbPrefix(adbCommand);

            Result<String> deviceCheck = verifyDevice(adbPrefix);
            if (deviceCheck instanceof Failure<String> failure) {
                return new Failure<>(null, failure.description());
            }

            // Dump view hierarchy to find scrollable view
            Result<ViewHierarchy> hierarchyResult = dumpViewHierarchy();
            if (hierarchyResult instanceof Failure<ViewHierarchy> failure) {
                return new Failure<>(null, "Failed to dump view hierarchy: " + failure.description());
            }

            ViewHierarchy hierarchy = ((Success<ViewHierarchy>) hierarchyResult).value();

            Result<ViewNode> parseResult = ViewHierarchyParser.parse(hierarchy.getXmlContent());
            if (parseResult instanceof Failure<ViewNode> failure) {
                return new Failure<>(null, "Failed to parse view hierarchy: " + failure.description());
            }

            ViewNode rootNode = ((Success<ViewNode>) parseResult).value();

            Optional<ViewNode> scrollableViewOpt = findScrollableView(rootNode);
            if (scrollableViewOpt.isEmpty()) {
                return new Failure<>(null, "Could not find scrollable view");
            }

            ViewNode scrollableView = scrollableViewOpt.get();
            ViewNode.Bounds scrollBounds = scrollableView.getBounds();
            int scrollableHeight = scrollBounds.getHeight();

            // Calculate scroll parameters
            // Scroll 50% of scrollable height, but skip only 40% to leave a 10% safety buffer
            // This accounts for scroll imprecision in Android's input swipe
            int actualScrollStep = (scrollStep != null) ? scrollStep : scrollableHeight / 2;
            // We'll use hash detection to find actual overlap, this is just the minimum expected
            int minExpectedOverlap = (int)(scrollableHeight * 0.4);

            if (debugMode) {
                System.err.printf("Scrollable bounds: [%d,%d,%d,%d] size=%dx%d%n",
                    scrollBounds.getLeft(), scrollBounds.getTop(),
                    scrollBounds.getRight(), scrollBounds.getBottom(),
                    scrollBounds.getWidth(), scrollableHeight);
                System.err.printf("Scroll step: %d pixels (50%% of height)%n", actualScrollStep);
                System.err.printf("Min expected overlap: %d pixels (40%%)%n", minExpectedOverlap);
            }

            // For detecting actual overlap
            ImageOverlapDetector overlapDetector = new ImageOverlapDetector();

            // Create swipe controller
            AdbSwipeController swipeController = AdbSwipeController.builder()
                .deviceSerial(deviceSerial)
                .adbPath(adbPath)
                .timeoutSeconds(timeoutSeconds)
                .build();

            // Scroll to top if requested
            if (scrollToTop) {
                Result<Void> scrollResult = swipeController.scrollToTopSimple(scrollBounds, scrollToTopSwipes);
                if (scrollResult instanceof Failure<Void> failure) {
                    return new Failure<>(null, "Failed to scroll to top: " + failure.description());
                }
                Thread.sleep(scrollDelayMs);
            }

            // ============== CAPTURE FIRST SCREENSHOT ==============
            Result<BufferedImage> firstCapture = captureScreenshot(adbPrefix);
            if (firstCapture instanceof Failure<BufferedImage> failure) {
                return new Failure<>(null, "Failed to capture first screenshot: " + failure.description());
            }

            BufferedImage firstScreen = ((Success<BufferedImage>) firstCapture).value();
            int captureCount = 1;

            if (debugMode) {
                saveDebugImage(firstScreen, captureCount);
            }

            // Compute hashes for scroll-end detection
            long[] prevHashes = hashCalculator.computeRowHashes(firstScreen, scrollBounds);

            // Build the final image using Graphics2D
            // Estimate max height (will resize if needed)
            int estimatedHeight = firstScreen.getHeight() * maxCaptures;
            int imageWidth = firstScreen.getWidth();
            BufferedImage resultImage = new BufferedImage(imageWidth, estimatedHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = resultImage.createGraphics();

            // First screenshot: draw from top to bottom of scrollable area (excludes bottom nav)
            // We'll add the bottom nav from the last screenshot
            int firstSegmentHeight = scrollBounds.getBottom();
            g2d.drawImage(firstScreen,
                0, 0, imageWidth, firstSegmentHeight,      // destination
                0, 0, imageWidth, firstSegmentHeight,      // source
                null);
            int currentY = firstSegmentHeight;

            if (debugMode) {
                System.err.printf("Capture 1: top to scrollable bottom, height=%d%n", firstSegmentHeight);
            }

            boolean reachedEnd = false;
            BufferedImage lastScreen = firstScreen;
            int blendHeight = 20;  // Pixels to blend at seams

            // ============== SUBSEQUENT SCREENSHOTS ==============
            while (captureCount < maxCaptures) {
                // Scroll by 50% of scrollable height
                Result<Void> swipeResult = performScroll(scrollBounds, actualScrollStep);
                if (swipeResult instanceof Failure<Void>) {
                    break;
                }

                Thread.sleep(scrollDelayMs);

                // Capture next screenshot
                Result<BufferedImage> nextCapture = captureScreenshot(adbPrefix);
                if (nextCapture instanceof Failure<BufferedImage> failure) {
                    break;
                }

                BufferedImage screen = ((Success<BufferedImage>) nextCapture).value();
                captureCount++;

                if (debugMode) {
                    saveDebugImage(screen, captureCount);
                }

                // Check for scroll end using hashes
                long[] currHashes = hashCalculator.computeRowHashes(screen, scrollBounds);
                if (isScrollEnd(prevHashes, currHashes)) {
                    reachedEnd = true;
                    lastScreen = screen;
                    if (debugMode) {
                        System.err.printf("Capture %d: scroll end detected%n", captureCount);
                    }
                    break;
                }

                // Find actual overlap using hash comparison
                Optional<ImageOverlapDetector.OverlapResult> overlapOpt =
                    overlapDetector.findOverlap(prevHashes, currHashes);

                int actualOverlap;
                if (overlapOpt.isPresent()) {
                    actualOverlap = overlapOpt.get().overlapRows();
                    if (debugMode) {
                        System.err.printf("Capture %d: detected overlap=%d rows%n", captureCount, actualOverlap);
                    }
                } else {
                    // Fallback to minimum expected overlap
                    actualOverlap = minExpectedOverlap;
                    if (debugMode) {
                        System.err.printf("Capture %d: no overlap detected, using fallback=%d%n", captureCount, actualOverlap);
                    }
                }

                // OVERWRITE approach: draw the new screenshot's scrollable area over the overlap
                // This ensures seamless stitching because both sides of the "seam" come from the same image
                int scrollTop = scrollBounds.getTop();
                int scrollBottom = scrollBounds.getBottom();
                int scrollHeight = scrollBottom - scrollTop;

                // Calculate where to start drawing in destination
                // The overlap region in destination starts at: currentY - actualOverlap
                // We'll draw the entire scrollable area from the new screenshot, overwriting the overlap
                int destStartY = currentY - actualOverlap;

                // Draw the entire scrollable region from the new screenshot
                g2d.drawImage(screen,
                    0, destStartY, imageWidth, destStartY + scrollHeight,
                    0, scrollTop, imageWidth, scrollBottom,
                    null);

                // The new currentY is where we finished drawing
                int newContentAdded = scrollHeight - actualOverlap;
                currentY = destStartY + scrollHeight;

                if (debugMode) {
                    System.err.printf("Capture %d: overlap=%d, overwrote from %d, added=%d, total=%d%n",
                        captureCount, actualOverlap, destStartY, newContentAdded, currentY);
                }

                prevHashes = currHashes;
                lastScreen = screen;
            }

            // ============== EXTEND LAST SCREENSHOT TO SCREEN BOTTOM ==============
            // Include any static bottom navigation/bar
            if (captureCount > 1) {
                int bottomExtra = lastScreen.getHeight() - scrollBounds.getBottom();
                if (bottomExtra > 0) {
                    g2d.drawImage(lastScreen,
                        0, currentY, imageWidth, currentY + bottomExtra,                // destination
                        0, scrollBounds.getBottom(), imageWidth, lastScreen.getHeight(), // source
                        null);
                    currentY += bottomExtra;

                    if (debugMode) {
                        System.err.printf("Extended with bottom nav: +%d pixels%n", bottomExtra);
                    }
                }
            }

            g2d.dispose();

            // Crop to actual height
            int finalHeight = currentY;
            BufferedImage finalImage = resultImage.getSubimage(0, 0, imageWidth, finalHeight);

            if (debugMode) {
                System.err.printf("Final image: %dx%d from %d captures%n",
                    imageWidth, finalHeight, captureCount);
            }

            // Save result
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    return new Failure<>(null, "Failed to create output directory");
                }
            }

            // Write to a temp file first, then move (subimage can't be written directly)
            BufferedImage outputImage = new BufferedImage(imageWidth, finalHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D outG2d = outputImage.createGraphics();
            outG2d.drawImage(finalImage, 0, 0, null);
            outG2d.dispose();

            if (!ImageIO.write(outputImage, "PNG", outputFile)) {
                return new Failure<>(null, "Failed to write PNG file");
            }

            ScrollScreenshot screenshot = ScrollScreenshot.builder()
                .file(outputFile)
                .deviceSerial(deviceSerial)
                .width(imageWidth)
                .height(finalHeight)
                .captureCount(captureCount)
                .scrollableViewId(scrollableView.getResourceId())
                .scrollableBounds(scrollBounds)
                .reachedScrollEnd(reachedEnd)
                .build();

            return new Success<>(screenshot,
                String.format("Captured %d screenshots, final size %dx%d%s",
                    captureCount, imageWidth, finalHeight,
                    reachedEnd ? " (reached end)" : ""));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure<>(null, "Capture interrupted");
        } catch (Exception e) {
            return new Failure<>(null, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Performs a scroll by the specified pixel amount.
     */
    private Result<Void> performScroll(ViewNode.Bounds bounds, int scrollAmount) {
        int centerX = bounds.getCenterX();
        int margin = (int) (bounds.getHeight() * 0.1);  // 10% margin from edges

        int startY = bounds.getBottom() - margin;
        int endY = startY - scrollAmount;

        // Clamp to bounds
        endY = Math.max(bounds.getTop() + margin, endY);

        return executeSwipe(centerX, startY, centerX, endY, swipeDurationMs);
    }

    /**
     * Finds a uniform row (low color variance) near the target position.
     * This helps avoid cutting through shadows, gradients, or visual elements.
     *
     * @param image The screenshot
     * @param targetY The ideal cut position
     * @param searchRange How many pixels above/below to search
     * @return The Y coordinate of the most uniform row
     */
    private int findUniformRow(BufferedImage image, int targetY, int searchRange) {
        int minY = Math.max(0, targetY - searchRange);
        int maxY = Math.min(image.getHeight() - 1, targetY + searchRange);

        int bestY = targetY;
        double bestScore = Double.MAX_VALUE;

        for (int y = minY; y <= maxY; y++) {
            double variance = calculateRowVariance(image, y);
            // Slight preference for rows closer to target
            double distancePenalty = Math.abs(y - targetY) * 0.5;
            double score = variance + distancePenalty;

            if (score < bestScore) {
                bestScore = score;
                bestY = y;
            }
        }

        return bestY;
    }

    /**
     * Calculates color variance of a row (lower = more uniform).
     */
    private double calculateRowVariance(BufferedImage image, int y) {
        int width = image.getWidth();
        if (width == 0) return Double.MAX_VALUE;

        int sampleStep = 8;  // Sample every 8th pixel
        int samples = width / sampleStep;
        if (samples < 2) return Double.MAX_VALUE;

        long sumR = 0, sumG = 0, sumB = 0;
        long sumR2 = 0, sumG2 = 0, sumB2 = 0;

        for (int x = 0; x < width; x += sampleStep) {
            int rgb = image.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            sumR += r; sumG += g; sumB += b;
            sumR2 += r * r; sumG2 += g * g; sumB2 += b * b;
        }

        double varR = (sumR2 / (double) samples) - Math.pow(sumR / (double) samples, 2);
        double varG = (sumG2 / (double) samples) - Math.pow(sumG / (double) samples, 2);
        double varB = (sumB2 / (double) samples) - Math.pow(sumB / (double) samples, 2);

        return varR + varG + varB;
    }

    /**
     * Finds the best row to cut at by comparing pixels between the destination image
     * and the new screenshot. Looks for a row with minimal pixel difference.
     *
     * @param destImage The image we've built so far
     * @param srcImage The new screenshot
     * @param destCurrentY Current Y position in destination (bottom of what we've drawn)
     * @param scrollBounds Bounds of the scrollable area
     * @param detectedOverlap Number of overlapping rows detected by hash comparison
     * @return The Y coordinate in srcImage where we should start taking content
     */
    private int findBestCutRow(BufferedImage destImage, BufferedImage srcImage,
                                int destCurrentY, ViewNode.Bounds scrollBounds, int detectedOverlap) {
        int scrollTop = scrollBounds.getTop();
        int scrollBottom = scrollBounds.getBottom();
        int width = srcImage.getWidth();

        // Search range: from 20% into the overlap to 80% into the overlap
        // This avoids cutting too close to edges
        int searchStart = scrollTop + (int)(detectedOverlap * 0.2);
        int searchEnd = scrollTop + (int)(detectedOverlap * 0.8);

        // Ensure valid range
        searchStart = Math.max(scrollTop + 10, searchStart);
        searchEnd = Math.min(scrollBottom - 10, searchEnd);

        if (searchEnd <= searchStart) {
            // Fallback to middle of detected overlap
            return scrollTop + detectedOverlap / 2;
        }

        int bestRow = searchStart;
        long bestDiff = Long.MAX_VALUE;

        // For each candidate row in the source image
        for (int srcY = searchStart; srcY < searchEnd; srcY++) {
            // Calculate corresponding Y in destination
            // Content at srcY in new image = content at (destCurrentY - overlap + srcY - scrollTop) in dest
            // Simplified: destY = destCurrentY - (scrollBottom - srcY)
            int destY = destCurrentY - (scrollBottom - srcY);

            if (destY < 0 || destY >= destImage.getHeight()) {
                continue;
            }

            // Compare this row between source and destination
            long diff = compareRows(destImage, srcImage, destY, srcY, width);

            if (diff < bestDiff) {
                bestDiff = diff;
                bestRow = srcY;

                // If we find an exact match, use it immediately
                if (diff == 0) {
                    break;
                }
            }
        }

        return bestRow;
    }

    /**
     * Compares two rows of pixels and returns the total difference.
     */
    private long compareRows(BufferedImage img1, BufferedImage img2, int y1, int y2, int width) {
        long totalDiff = 0;

        // Sample every 4th pixel for speed
        for (int x = 0; x < width; x += 4) {
            int rgb1 = img1.getRGB(x, y1);
            int rgb2 = img2.getRGB(x, y2);

            int r1 = (rgb1 >> 16) & 0xFF;
            int g1 = (rgb1 >> 8) & 0xFF;
            int b1 = rgb1 & 0xFF;

            int r2 = (rgb2 >> 16) & 0xFF;
            int g2 = (rgb2 >> 8) & 0xFF;
            int b2 = rgb2 & 0xFF;

            totalDiff += Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
        }

        return totalDiff;
    }

    /**
     * Draws a segment with alpha blending at the top edge to smooth seams.
     * This helps hide visible lines when cutting through shadows or gradients.
     */
    private void drawWithBlending(Graphics2D g2d, BufferedImage source, int destY,
                                   int srcTop, int srcBottom, int width, int blendHeight) {
        int segmentHeight = srcBottom - srcTop;

        // If segment is too small for blending, just draw normally
        if (segmentHeight <= blendHeight * 2) {
            g2d.drawImage(source,
                0, destY, width, destY + segmentHeight,
                0, srcTop, width, srcBottom,
                null);
            return;
        }

        // Draw the main part (after blend zone) normally
        g2d.drawImage(source,
            0, destY + blendHeight, width, destY + segmentHeight,
            0, srcTop + blendHeight, width, srcBottom,
            null);

        // Draw the blend zone with alpha gradient
        for (int i = 0; i < blendHeight; i++) {
            float alpha = (float) i / blendHeight;  // 0.0 at top, 1.0 at bottom

            int srcY = srcTop + i;
            int dstY = destY + i;

            // Get pixels from source
            int[] srcPixels = new int[width];
            source.getRGB(0, srcY, width, 1, srcPixels, 0, width);

            // Get existing pixels from destination (for blending)
            int[] dstPixels = new int[width];
            // Read from the result image at the position where we're blending
            // This assumes the previous segment already drew there

            // Blend and draw
            for (int x = 0; x < width; x++) {
                int srcRGB = srcPixels[x];
                int srcR = (srcRGB >> 16) & 0xFF;
                int srcG = (srcRGB >> 8) & 0xFF;
                int srcB = srcRGB & 0xFF;

                // Create blended pixel (blend with existing content)
                // For simplicity, we use the source with alpha applied
                int newR = (int)(srcR * alpha + srcR * (1 - alpha));
                int newG = (int)(srcG * alpha + srcG * (1 - alpha));
                int newB = (int)(srcB * alpha + srcB * (1 - alpha));

                srcPixels[x] = (0xFF << 24) | (newR << 16) | (newG << 8) | newB;
            }

            // Draw the blended row
            BufferedImage rowImage = new BufferedImage(width, 1, BufferedImage.TYPE_INT_ARGB);
            rowImage.setRGB(0, 0, width, 1, srcPixels, 0, width);

            java.awt.Composite oldComposite = g2d.getComposite();
            g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
            g2d.drawImage(rowImage, 0, dstY, null);
            g2d.setComposite(oldComposite);
        }
    }

    /**
     * Executes an ADB swipe command.
     */
    private Result<Void> executeSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        try {
            String adbCommand = adbPath != null ? adbPath : "adb";
            List<String> command = new ArrayList<>();
            command.add(adbCommand);

            if (deviceSerial != null && !deviceSerial.isEmpty()) {
                command.add("-s");
                command.add(deviceSerial);
            }

            command.add("shell");
            command.add("input");
            command.add("swipe");
            command.add(String.valueOf(x1));
            command.add(String.valueOf(y1));
            command.add(String.valueOf(x2));
            command.add(String.valueOf(y2));
            command.add(String.valueOf(durationMs));

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "Swipe timed out");
            }

            if (process.exitValue() != 0) {
                return new Failure<>(null, "Swipe failed");
            }

            return new Success<>(null, "Swipe completed");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Swipe error: " + e.getMessage());
        }
    }

    /**
     * Checks if scroll has ended by comparing hash arrays.
     */
    private boolean isScrollEnd(long[] prevHashes, long[] currHashes) {
        if (prevHashes == null || currHashes == null ||
            prevHashes.length != currHashes.length) {
            return false;
        }

        int matches = 0;
        for (int i = 0; i < prevHashes.length; i++) {
            if (prevHashes[i] == currHashes[i]) {
                matches++;
            }
        }

        double matchRatio = (double) matches / prevHashes.length;
        return matchRatio >= 0.95;  // 95% match = scroll ended
    }

    private Optional<ViewNode> findScrollableView(ViewNode root) {
        if (targetViewId != null && !targetViewId.isEmpty()) {
            return viewFinder.findByResourceId(root, targetViewId);
        }
        return viewFinder.findDefaultScrollable(root);
    }

    private Result<ViewHierarchy> dumpViewHierarchy() {
        return ViewHierarchyDumper.builder()
            .deviceSerial(deviceSerial)
            .adbPath(adbPath)
            .timeoutSeconds(timeoutSeconds)
            .compressed(false)
            .build()
            .dump();
    }

    private Result<BufferedImage> captureScreenshot(List<String> adbPrefix) {
        try {
            Path tempFile = Files.createTempFile("fixed_scroll_", ".png");
            String devicePath = "/sdcard/fixed_scroll_temp.png";

            List<String> captureCmd = new ArrayList<>(adbPrefix);
            captureCmd.add("shell");
            captureCmd.add("screencap");
            captureCmd.add("-p");
            captureCmd.add(devicePath);

            ProcessBuilder pb = new ProcessBuilder(captureCmd);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "Screencap failed");
            }

            List<String> pullCmd = new ArrayList<>(adbPrefix);
            pullCmd.add("pull");
            pullCmd.add(devicePath);
            pullCmd.add(tempFile.toString());

            pb = new ProcessBuilder(pullCmd);
            process = pb.start();
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "Pull failed");
            }

            // Cleanup device file
            cleanupDeviceFile(adbPrefix, devicePath);

            BufferedImage image = ImageIO.read(tempFile.toFile());
            Files.deleteIfExists(tempFile);

            if (image == null) {
                return new Failure<>(null, "Failed to read image");
            }

            return new Success<>(image, "Screenshot captured");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Screenshot error: " + e.getMessage());
        }
    }

    private void cleanupDeviceFile(List<String> adbPrefix, String devicePath) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("shell");
            command.add("rm");
            command.add("-f");
            command.add(devicePath);
            new ProcessBuilder(command).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void saveDebugImage(BufferedImage image, int index) {
        if (outputFile != null) {
            String debugPath = outputFile.getAbsolutePath()
                .replace(".png", "-capture" + index + ".png");
            try {
                ImageIO.write(image, "PNG", new File(debugPath));
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private List<String> buildAdbPrefix(String adbCommand) {
        List<String> prefix = new ArrayList<>();
        prefix.add(adbCommand);
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            prefix.add("-s");
            prefix.add(deviceSerial);
        }
        return prefix;
    }

    private Result<Void> verifyAdb(String adbCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder(adbCommand, "version");
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                return new Failure<>(null, "ADB not accessible");
            }
            return new Success<>(null, "ADB verified");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "ADB not found");
        }
    }

    private Result<String> verifyDevice(List<String> adbPrefix) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("get-state");

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                return new Failure<>(null, "No device connected");
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String state = reader.readLine();
            reader.close();

            if (!"device".equals(state != null ? state.trim() : "")) {
                return new Failure<>(null, "Device not ready: " + state);
            }

            return new Success<>(state, "Device verified");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Device check failed");
        }
    }
}
