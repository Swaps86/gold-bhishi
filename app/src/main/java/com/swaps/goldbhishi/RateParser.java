package com.swaps.goldbhishi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads 22K and 24K per-gram rates out of a gold-rate web page. No Android classes, so it can be tested anywhere. */
final class RateParser {

    static final class Result {
        double r22;
        double r24;
        String date;
    }

    private static final String RUPEE = "₹";

    private static final Pattern NUM = Pattern.compile(
            "(?:" + RUPEE + "|Rs\\.?|INR)?\\s*(\\d{1,3}(?:,\\d{2,3})+|\\d{4,6})(?:\\.\\d+)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE = Pattern.compile(
            "(\\d{1,2})(?:st|nd|rd|th)?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?,?\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    static Result parse(String html) {
        if (html == null) return null;
        String t = html
                .replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&#8377;", RUPEE)
                .replace("&#x20B9;", RUPEE)
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");

        List<Double> c24 = candidates(t, 24);
        List<Double> c22 = candidates(t, 22);

        for (double x : c24) {
            for (double y : c22) {
                // 22K is always cheaper than 24K, and not by a huge margin
                if (y < x && y > x * 0.85) {
                    Result r = new Result();
                    r.r22 = y;
                    r.r24 = x;
                    r.date = findDate(t);
                    return r;
                }
            }
        }
        return null;
    }

    private static List<Double> candidates(String text, int karat) {
        List<Double> out = new ArrayList<>();
        Pattern label = Pattern.compile("\\b" + karat + "\\s?(?:K|Kt|Karat|Carat|Ct)\\b", Pattern.CASE_INSENSITIVE);
        Matcher m = label.matcher(text);
        while (m.find()) {
            int end = Math.min(text.length(), m.end() + 200);
            String window = text.substring(m.end(), end);
            Matcher n = NUM.matcher(window);
            while (n.find()) {
                double v;
                try {
                    v = Double.parseDouble(n.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    continue;
                }
                // a per-gram price today is in this range; this skips 8g / 10g prices and years
                if (v >= 5000 && v <= 60000) {
                    out.add(v);
                    break;
                }
            }
        }
        return out;
    }

    private static String findDate(String text) {
        Matcher m = DATE.matcher(text);
        if (!m.find()) return null;
        String mon = m.group(2).substring(0, 1).toUpperCase() + m.group(2).substring(1).toLowerCase();
        return Integer.parseInt(m.group(1)) + " " + mon + " " + m.group(3);
    }
}
