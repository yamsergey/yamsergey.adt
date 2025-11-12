package io.yamsergey.adt.tools.android.drawables.renderer;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Renders Android vector drawable XML files to PNG images.
 *
 * Android vector drawables use a custom XML format that's similar but not
 * identical to SVG. This renderer:
 * 1. Converts Android vector drawable XML to SVG format
 * 2. Uses Apache Batik to render the SVG to PNG
 */
public class VectorDrawableRenderer {

  private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
  private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";

  /**
   * Render an Android vector drawable to a PNG file.
   *
   * @param vectorDrawablePath Path to the Android vector drawable XML file
   * @param outputPath Path where the PNG should be written
   * @param width Output image width in pixels (or null for default from XML)
   * @param height Output image height in pixels (or null for default from XML)
   * @throws IOException If reading/writing files fails
   */
  public void renderToPng(Path vectorDrawablePath, Path outputPath, Integer width, Integer height)
      throws IOException {
    try {
      // Convert Android vector drawable to SVG
      Document svgDocument = convertToSvg(vectorDrawablePath);

      // Write SVG to temporary file
      Path tempSvgPath = Files.createTempFile("vector_drawable_", ".svg");
      try {
        writeSvgToFile(svgDocument, tempSvgPath);

        // Render SVG to PNG using Batik
        renderSvgToPng(tempSvgPath, outputPath, width, height);
      } finally {
        Files.deleteIfExists(tempSvgPath);
      }
    } catch (Exception e) {
      throw new IOException("Failed to render vector drawable: " + e.getMessage(), e);
    }
  }

  /**
   * Convert Android vector drawable XML to SVG format.
   */
  private Document convertToSvg(Path vectorDrawablePath) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();

    Document androidDoc = builder.parse(vectorDrawablePath.toFile());
    Element vectorElement = androidDoc.getDocumentElement();

    // Create SVG document
    Document svgDoc = builder.newDocument();
    Element svgElement = svgDoc.createElementNS(SVG_NAMESPACE, "svg");
    svgDoc.appendChild(svgElement);

    // Extract dimensions from android:width and android:height
    String widthStr = vectorElement.getAttributeNS(ANDROID_NAMESPACE, "width");
    String heightStr = vectorElement.getAttributeNS(ANDROID_NAMESPACE, "height");
    String viewportWidth = vectorElement.getAttributeNS(ANDROID_NAMESPACE, "viewportWidth");
    String viewportHeight = vectorElement.getAttributeNS(ANDROID_NAMESPACE, "viewportHeight");

    if (!widthStr.isEmpty()) {
      svgElement.setAttribute("width", convertDpToPx(widthStr));
    }
    if (!heightStr.isEmpty()) {
      svgElement.setAttribute("height", convertDpToPx(heightStr));
    }
    if (!viewportWidth.isEmpty() && !viewportHeight.isEmpty()) {
      svgElement.setAttribute("viewBox", String.format("0 0 %s %s", viewportWidth, viewportHeight));
    }

    // Convert path elements
    var pathElements = vectorElement.getElementsByTagName("path");
    for (int i = 0; i < pathElements.getLength(); i++) {
      Element androidPath = (Element) pathElements.item(i);
      Element svgPath = svgDoc.createElementNS(SVG_NAMESPACE, "path");

      // Copy path data
      String pathData = androidPath.getAttributeNS(ANDROID_NAMESPACE, "pathData");
      if (!pathData.isEmpty()) {
        svgPath.setAttribute("d", pathData);
      }

      // Copy fill color
      String fillColor = androidPath.getAttributeNS(ANDROID_NAMESPACE, "fillColor");
      if (!fillColor.isEmpty()) {
        svgPath.setAttribute("fill", convertColor(fillColor));
      }

      // Copy stroke attributes
      String strokeColor = androidPath.getAttributeNS(ANDROID_NAMESPACE, "strokeColor");
      if (!strokeColor.isEmpty()) {
        svgPath.setAttribute("stroke", convertColor(strokeColor));
      }

      String strokeWidth = androidPath.getAttributeNS(ANDROID_NAMESPACE, "strokeWidth");
      if (!strokeWidth.isEmpty()) {
        svgPath.setAttribute("stroke-width", strokeWidth);
      }

      // Copy fill type
      String fillType = androidPath.getAttributeNS(ANDROID_NAMESPACE, "fillType");
      if ("evenOdd".equals(fillType)) {
        svgPath.setAttribute("fill-rule", "evenodd");
      }

      svgElement.appendChild(svgPath);
    }

    return svgDoc;
  }

  /**
   * Convert Android dp units to pixels (assumes 1dp = 1px for simplicity).
   */
  private String convertDpToPx(String dpValue) {
    if (dpValue.endsWith("dp")) {
      return dpValue.substring(0, dpValue.length() - 2) + "px";
    }
    return dpValue;
  }

  /**
   * Convert Android color format (#AARRGGBB or #RRGGBB) to SVG format.
   * Also resolves Android color references like @android:color/white.
   */
  private String convertColor(String androidColor) {
    if (androidColor.startsWith("#")) {
      if (androidColor.length() == 9) {
        // #AARRGGBB - extract RGB and handle alpha separately if needed
        return "#" + androidColor.substring(3);
      }
      return androidColor; // #RRGGBB is already compatible
    }

    // Handle Android color resource references
    if (androidColor.startsWith("@android:color/") || androidColor.startsWith("@color/")) {
      return resolveAndroidColorReference(androidColor);
    }

    return androidColor;
  }

  /**
   * Resolve Android color resource references to actual color values.
   * Common Android system colors are mapped to their standard values.
   */
  private String resolveAndroidColorReference(String colorRef) {
    // Remove @android:color/ or @color/ prefix
    String colorName = colorRef.replace("@android:color/", "").replace("@color/", "");

    // Map common Android system colors
    return switch (colorName) {
      case "white" -> "#FFFFFF";
      case "black" -> "#000000";
      case "transparent" -> "#00000000";
      case "darker_gray" -> "#AAAAAA";
      case "background_dark" -> "#FF000000";
      case "background_light" -> "#FFFFFFFF";
      case "holo_blue_light" -> "#FF33B5E5";
      case "holo_green_light" -> "#FF99CC00";
      case "holo_red_light" -> "#FFFF4444";
      case "holo_orange_light" -> "#FFFFBB33";
      case "holo_purple" -> "#FFAA66CC";
      default -> "#000000"; // Default to black for unknown colors
    };
  }

  /**
   * Write SVG document to file.
   */
  private void writeSvgToFile(Document svgDoc, Path outputPath) throws Exception {
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    DOMSource source = new DOMSource(svgDoc);
    StreamResult result = new StreamResult(outputPath.toFile());
    transformer.transform(source, result);
  }

  /**
   * Render SVG file to PNG using Apache Batik.
   */
  private void renderSvgToPng(Path svgPath, Path outputPath, Integer width, Integer height)
      throws Exception {
    PNGTranscoder transcoder = new PNGTranscoder();

    if (width != null) {
      transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width.floatValue());
    }
    if (height != null) {
      transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height.floatValue());
    }

    try (FileInputStream inputStream = new FileInputStream(svgPath.toFile());
         FileOutputStream outputStream = new FileOutputStream(outputPath.toFile())) {

      TranscoderInput input = new TranscoderInput(inputStream);
      TranscoderOutput output = new TranscoderOutput(outputStream);

      transcoder.transcode(input, output);
    }
  }
}
