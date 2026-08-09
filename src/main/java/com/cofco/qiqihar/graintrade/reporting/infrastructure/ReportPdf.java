package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import com.cofco.qiqihar.graintrade.reporting.application.ReportPreviewView;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

/** Renders the immutable server-owned report preview into a portable PDF document. */
public final class ReportPdf {
    private static final int PAGE_WIDTH = 1240;
    private static final int PAGE_HEIGHT = 1754;
    private static final int MARGIN = 86;
    private static final int CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2;
    private static final Color BRAND = new Color(20, 119, 181);
    private static final Color INK = new Color(20, 53, 73);
    private static final Color MUTED = new Color(88, 112, 126);
    private static final Color PANEL = new Color(241, 247, 250);
    private static final String[] CJK_FONT_CANDIDATES = {
        "Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "PingFang SC",
        "Microsoft YaHei", "WenQuanYi Zen Hei", "Arial Unicode MS", "SansSerif"
    };

    private ReportPdf() {}

    public static byte[] create(ReportPreviewView preview) {
        if (preview == null) {
            throw new IllegalArgumentException("preview");
        }
        try {
            FontFamily fonts = selectFonts(reportText(preview));
            return encode(render(preview, fonts));
        } catch (IOException exception) {
            throw new IllegalStateException("REPORT_PDF_GENERATION_FAILED", exception);
        }
    }

    private static List<BufferedImage> render(ReportPreviewView preview, FontFamily fonts) {
        List<BufferedImage> pages = new ArrayList<>();
        Page page = newPage(preview, fonts, pages, 1);

        page.sectionHeading("关键指标", fonts);
        for (ReportPreviewView.Line line : preview.lines()) {
            List<String> valueLines = wrap(line.value(), page.graphics, fonts.body(), CONTENT_WIDTH - 340);
            List<String> noteLines = wrap(line.note(), page.graphics, fonts.small(), CONTENT_WIDTH - 340);
            int rowHeight = Math.max(92, 34 + valueLines.size() * 34 + noteLines.size() * 27);
            if (!page.hasRoom(rowHeight + 20)) {
                page.dispose();
                page = newPage(preview, fonts, pages, pages.size() + 1);
                page.sectionHeading("关键指标（续）", fonts);
            }
            page.panel(page.y, rowHeight);
            page.text(line.label(), MARGIN + 28, page.y + 48, fonts.label(), INK);
            int valueY = page.y + 42;
            for (String value : valueLines) {
                page.text(value, MARGIN + 320, valueY, fonts.body(), INK);
                valueY += 34;
            }
            for (String note : noteLines) {
                page.text(note, MARGIN + 320, valueY, fonts.small(), MUTED);
                valueY += 27;
            }
            page.y += rowHeight + 18;
        }

        page.sectionHeading("报告正文", fonts);
        for (ReportPreviewView.Section section : preview.sections()) {
            List<String> body = wrap(section.body(), page.graphics, fonts.body(), CONTENT_WIDTH - 56);
            int blockHeight = 78 + body.size() * 34;
            if (!page.hasRoom(blockHeight + 20)) {
                page.dispose();
                page = newPage(preview, fonts, pages, pages.size() + 1);
                page.sectionHeading("报告正文（续）", fonts);
            }
            page.panel(page.y, blockHeight);
            page.text(section.title(), MARGIN + 28, page.y + 46, fonts.label(), INK);
            int bodyY = page.y + 88;
            for (String line : body) {
                page.text(line, MARGIN + 28, bodyY, fonts.body(), INK);
                bodyY += 34;
            }
            page.y += blockHeight + 18;
        }
        page.dispose();

        for (int index = 0; index < pages.size(); index++) {
            Graphics2D graphics = pages.get(index).createGraphics();
            configure(graphics);
            graphics.setFont(fonts.small());
            graphics.setColor(MUTED);
            String footer = "本报告依据服务端核定快照生成  ·  第 " + (index + 1) + " / " + pages.size() + " 页";
            int width = graphics.getFontMetrics().stringWidth(footer);
            graphics.drawString(footer, PAGE_WIDTH - MARGIN - width, PAGE_HEIGHT - 48);
            graphics.dispose();
        }
        return pages;
    }

    private static Page newPage(
            ReportPreviewView preview, FontFamily fonts, List<BufferedImage> pages, int pageNumber) {
        BufferedImage image = new BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        configure(graphics);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
        graphics.setColor(BRAND);
        graphics.fillRect(0, 0, PAGE_WIDTH, 24);
        graphics.setFont(fonts.title());
        graphics.setColor(INK);
        graphics.drawString(preview.title(), MARGIN, 126);
        graphics.setFont(fonts.body());
        graphics.setColor(MUTED);
        graphics.drawString("统计期间：" + preview.dataCutoffLabel(), MARGIN, 177);
        if (pageNumber > 1) {
            String continued = "续页 " + pageNumber;
            graphics.drawString(continued, PAGE_WIDTH - MARGIN - graphics.getFontMetrics().stringWidth(continued), 177);
        }
        graphics.setColor(new Color(203, 221, 231));
        graphics.drawLine(MARGIN, 205, PAGE_WIDTH - MARGIN, 205);
        pages.add(image);
        return new Page(graphics, 245);
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static FontFamily selectFonts(String requiredText) {
        Set<String> installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT));
        for (String candidate : CJK_FONT_CANDIDATES) {
            if (!candidate.equals("SansSerif") && !installed.contains(candidate)) {
                continue;
            }
            Font probe = new Font(candidate, Font.PLAIN, 24);
            if (probe.canDisplayUpTo(requiredText) < 0) {
                return new FontFamily(
                        new Font(candidate, Font.BOLD, 46),
                        new Font(candidate, Font.BOLD, 28),
                        new Font(candidate, Font.BOLD, 24),
                        new Font(candidate, Font.PLAIN, 24),
                        new Font(candidate, Font.PLAIN, 20));
            }
        }
        throw new IllegalStateException("REPORT_PDF_CJK_FONT_UNAVAILABLE");
    }

    private static String reportText(ReportPreviewView preview) {
        StringBuilder text = new StringBuilder(preview.title()).append(preview.dataCutoffLabel())
                .append("统计期间关键指标报告正文本报告依据服务端核定快照生成续页");
        preview.lines().forEach(line -> text.append(line.label()).append(line.value()).append(line.note()));
        preview.sections().forEach(section -> text.append(section.title()).append(section.body()));
        return text.toString();
    }

    private static List<String> wrap(String value, Graphics2D graphics, Font font, int width) {
        String normalized = value == null || value.isBlank() ? "—" : value.strip();
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                lines.add(current.toString());
                current.setLength(0);
                continue;
            }
            String candidate = current + new String(Character.toChars(codePoint));
            if (!current.isEmpty() && metrics.stringWidth(candidate) > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.appendCodePoint(codePoint);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of("—") : lines;
    }

    private static byte[] encode(List<BufferedImage> pages) throws IOException {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        write(pdf, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
        int objectCount = 2 + pages.size() * 3;
        long[] offsets = new long[objectCount + 1];

        object(pdf, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int index = 0; index < pages.size(); index++) {
            kids.append(5 + index * 3).append(" 0 R ");
        }
        object(pdf, offsets, 2, "<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");

        for (int index = 0; index < pages.size(); index++) {
            int imageObject = 3 + index * 3;
            int contentObject = imageObject + 1;
            int pageObject = imageObject + 2;
            byte[] image = compressedRgb(pages.get(index));
            streamObject(pdf, offsets, imageObject,
                    "<< /Type /XObject /Subtype /Image /Width " + PAGE_WIDTH + " /Height " + PAGE_HEIGHT
                            + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "
                            + image.length + " >>",
                    image);
            byte[] content = ("q\n595 0 0 842 0 0 cm\n/Im" + (index + 1) + " Do\nQ\n")
                    .getBytes(StandardCharsets.US_ASCII);
            streamObject(pdf, offsets, contentObject, "<< /Length " + content.length + " >>", content);
            object(pdf, offsets, pageObject,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im"
                            + (index + 1) + " " + imageObject + " 0 R >> >> /Contents " + contentObject + " 0 R >>");
        }

        long xref = pdf.size();
        write(pdf, "xref\n0 " + (objectCount + 1) + "\n");
        write(pdf, "0000000000 65535 f \n");
        for (int object = 1; object <= objectCount; object++) {
            write(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[object]));
        }
        write(pdf, "trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    private static byte[] compressedRgb(BufferedImage image) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            byte[] row = new byte[PAGE_WIDTH * 3];
            for (int y = 0; y < PAGE_HEIGHT; y++) {
                int cursor = 0;
                for (int x = 0; x < PAGE_WIDTH; x++) {
                    int rgb = image.getRGB(x, y);
                    row[cursor++] = (byte) (rgb >>> 16);
                    row[cursor++] = (byte) (rgb >>> 8);
                    row[cursor++] = (byte) rgb;
                }
                deflater.write(row);
            }
        }
        return compressed.toByteArray();
    }

    private static void object(ByteArrayOutputStream pdf, long[] offsets, int number, String body) throws IOException {
        offsets[number] = pdf.size();
        write(pdf, number + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void streamObject(
            ByteArrayOutputStream pdf, long[] offsets, int number, String dictionary, byte[] content) throws IOException {
        offsets[number] = pdf.size();
        write(pdf, number + " 0 obj\n" + dictionary + "\nstream\n");
        pdf.write(content);
        write(pdf, "\nendstream\nendobj\n");
    }

    private static void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private record FontFamily(Font title, Font section, Font label, Font body, Font small) {}

    private static final class Page {
        private final Graphics2D graphics;
        private int y;

        private Page(Graphics2D graphics, int y) {
            this.graphics = graphics;
            this.y = y;
        }

        private boolean hasRoom(int height) {
            return y + height < PAGE_HEIGHT - 110;
        }

        private void sectionHeading(String value, FontFamily fonts) {
            text(value, MARGIN, y + 34, fonts.section(), BRAND);
            y += 65;
        }

        private void panel(int top, int height) {
            graphics.setColor(PANEL);
            graphics.fillRoundRect(MARGIN, top, CONTENT_WIDTH, height, 20, 20);
        }

        private void text(String value, int x, int baseline, Font font, Color color) {
            graphics.setFont(font);
            graphics.setColor(color);
            graphics.drawString(value == null || value.isBlank() ? "—" : value, x, baseline);
        }

        private void dispose() {
            graphics.dispose();
        }
    }
}
