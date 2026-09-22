package com.zcshou.route;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class GpxParser {

    private GpxParser() {}

    public static List<RoutePoint> parse(InputStream inputStream) throws Exception {
        List<RoutePoint> points = new ArrayList<>();

        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(inputStream, null);

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("trkpt".equals(name) || "rtept".equals(name) || "wpt".equals(name)) {
                    String lat = parser.getAttributeValue(null, "lat");
                    String lon = parser.getAttributeValue(null, "lon");
                    if (lat != null && lon != null) {
                        points.add(new RoutePoint(
                                Double.parseDouble(lat),
                                Double.parseDouble(lon)
                        ));
                    }
                }
            }
            event = parser.next();
        }

        return points;
    }
}
