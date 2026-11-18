package gsp.jackson;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import gsp.model.GSP_Data;

/**
 * Fault-tolerant Jackson JSON deserializer for {@link GSP_Data} objects.
 * Handles edge cases such as numeric overflows, negative IDs, comma-separated decimals,
 * null array elements, and non-finite floating-point values.
 */
public class GSP_DataDeserializer extends StdDeserializer<GSP_Data> {

    private static final Logger logger = LogManager.getLogger(GSP_DataDeserializer.class);

    // Matches integers, floating-point numbers (dot or comma), and scientific notation (e.g., 1e500, 5.24e+1)
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("-?\\d+([.,]\\d+)?([eE][+-]?\\d+)?");


    // Matches consecutive sequences of numeric digits
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    public GSP_DataDeserializer() {
        super(GSP_Data.class);
    }

    /**
     * Required constructor for base class compatibility and Jackson internal mechanics.
     *
     * @param vc the target class type
     */
    public GSP_DataDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public GSP_Data deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
        JsonNode tree = jp.getCodec().readTree(jp);
        GSP_Data rrData = new GSP_Data();

        if (tree == null || !tree.isObject()) {
            logger.warn("Expected a JSON object for GSP_Data, but received: {}", tree);
            return rrData;
        }

        // 1. Parse Complex ID (with strict 32-bit signed integer overflow protection)
        JsonNode complexNode = tree.path("complex");
        if (complexNode.isNumber()) {
            if (complexNode.canConvertToInt()) {
                int rawId = complexNode.asInt();
                if (rawId == Integer.MIN_VALUE) {
                    logger.warn("Numeric complex ID equals Integer.MIN_VALUE and cannot be negated safely: {}", rawId);
                } else {
                    rrData.setId(Math.abs(rawId));
                }
            } else {
                logger.warn("Numeric complex ID exceeds Integer range limits: {}", complexNode.asText());
            }
        } else if (!complexNode.isMissingNode() && !complexNode.isNull()) {
            rrData.setId(parseIntSafe(complexNode.asText()));
        }

        // 2. Parse Latitude
        JsonNode latNode = tree.path("latitude");
        if (latNode.isNumber()) {
            double val = latNode.asDouble();
            rrData.setLatitude(Double.isFinite(val) ? val : null);
        } else if (!latNode.isMissingNode() && !latNode.isNull()) {
            rrData.setLatitude(parseDoubleSafe(latNode.asText()));
        }

        // 3. Parse Longitude
        JsonNode lonNode = tree.path("longitude");
        if (lonNode.isNumber()) {
            double val = lonNode.asDouble();
            rrData.setLongitude(Double.isFinite(val) ? val : null);
        } else if (!lonNode.isMissingNode() && !lonNode.isNull()) {
            rrData.setLongitude(parseDoubleSafe(lonNode.asText()));
        }

        // 4. Parse Text Fields (Fallback to empty strings if missing, null, or complex structure)
        JsonNode addressNode = tree.path("mailaddress");
        rrData.setAddress(extractTextSafe(addressNode));

        JsonNode districtNode = tree.path("district");
        rrData.setDistrict(extractTextSafe(districtNode));

        // 5. Parse Fuels Array (Filtering out null nodes, empty strings, and non-scalar elements)
        JsonNode fuelsNode = tree.path("fuels");
        if (fuelsNode.isArray()) {
            String fuelsString = StreamSupport
                    .stream(fuelsNode.spliterator(), false)
                    .filter(node -> node != null && !node.isNull() && node.isValueNode())
                    .map(node -> node.asText().trim())
                    .filter(text -> !text.isEmpty())
                    .collect(Collectors.joining(", "));
            rrData.setFuels(fuelsString);
        } else if (fuelsNode.isTextual()) {
            // Fallback in case upstream API returns fuels as a single string instead of an array
            rrData.setFuels(fuelsNode.asText().trim());
        }

        return rrData;
    }

    /**
     * Extracts text from scalar JSON nodes while safely ignoring nulls, arrays, and nested objects.
     */
    private String extractTextSafe(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return "";
        }
        return node.asText();
    }

    /**
     * Safely parses an Integer from a raw string.
     *
     * @param value the string to parse
     * @return the parsed absolute Integer, or null if the string is empty, invalid, or out of bounds
     */
    private Integer parseIntSafe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String trimmed = value.trim();
        try {
            long parsedLong = Long.parseLong(trimmed);

            // Prevent Integer.MIN_VALUE (-2147483648) where Math.abs() remains negative
            if (Math.abs(parsedLong) > Integer.MAX_VALUE) {
                logger.warn("Parsed value exceeds Integer range limits: '{}'", value);
                return null;
            }
            return (int) Math.abs(parsedLong);
        } catch (NumberFormatException e) {
            Matcher matcher = DIGIT_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                try {
                    long extractedLong = Long.parseLong(matcher.group());
                    if (extractedLong > Integer.MAX_VALUE) {
                        logger.warn("Extracted unsigned integer exceeds Integer range: '{}'", matcher.group());
                        return null;
                    }
                    return (int) extractedLong;
                } catch (NumberFormatException ex) {
                    logger.warn("Extracted digit sequence exceeds Long range limits: '{}'", matcher.group());
                    return null;
                }
            }
            logger.warn("Unable to extract a valid positive integer identifier from string: '{}'", value);
            return null;
        }
    }

    /**
     * Safely parses a Double, replacing commas with dots before final parsing.
     *
     * @param value the string to parse
     * @return the parsed finite Double, or null if no valid floating-point number is found
     */
    private Double parseDoubleSafe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        Matcher matcher = DOUBLE_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            logger.warn("Unable to extract a double (coordinate) from string: '{}'", value);
            return null;
        }

        String normalizedNumber = matcher.group().replace(',', '.');
        try {
            double parsed = Double.parseDouble(normalizedNumber);
            if (!Double.isFinite(parsed)) {
                logger.warn("Extracted coordinate is non-finite (Infinity/NaN): '{}' in original value: '{}'",
                        normalizedNumber, value);
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn("Extracted number exceeds Double range: '{}' in original value: '{}'", normalizedNumber, value);
            return null;
        }
    }
}
