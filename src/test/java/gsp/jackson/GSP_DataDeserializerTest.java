package gsp.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import gsp.model.GSP_Data;

class GSP_DataDeserializerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(GSP_Data.class, new GSP_DataDeserializer());
        objectMapper.registerModule(module);
    }

    @Test
    @DisplayName("Should correctly deserialize standard valid JSON payload")
    void shouldDeserializeStandardValidPayload() throws Exception {
        String json = """
                {
                  "complex": 42,
                  "latitude": 52.4345,
                  "longitude": 30.9754,
                  "mailaddress": "г. Гомель, ул. Советская, 10",
                  "district": "Центральный район",
                  "fuels": ["АИ-92", "АИ-95", "ДТ"]
                }
                """;

        GSP_Data result = objectMapper.readValue(json, GSP_Data.class);

        assertNotNull(result);
        assertEquals(42, result.getId());
        assertEquals(52.4345, result.getLatitude(), 0.00001);
        assertEquals(30.9754, result.getLongitude(), 0.00001);
        assertEquals("г. Гомель, ул. Советская, 10", result.getAddress());
        assertEquals("Центральный район", result.getDistrict());
        assertEquals("АИ-92, АИ-95, ДТ", result.getFuels());
    }

    @ParameterizedTest
    @CsvSource({
            "'-105', 105",
            "'  77  ', 77",
            "'АЗС № 314 (новая)', 314",
            "'ID-0089', 89",
            "'2147483647', 2147483647",
            "'-2147483647', 2147483647"
    })
    @DisplayName("Should extract positive Integer IDs from negative or alphanumeric strings")
    void shouldExtractPositiveIdsFromStrings(String rawComplex, int expectedId) throws Exception {
        String json = String.format("{\"complex\": \"%s\"}", rawComplex);

        GSP_Data result = objectMapper.readValue(json, GSP_Data.class);

        assertEquals(expectedId, result.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"2147483648\"",          // Integer.MAX_VALUE + 1 as string
            "\"-2147483648\"",         // Integer.MIN_VALUE as string (Math.abs overflow trap)
            "\"99999999999999999999\"", // Exceeds Long.MAX_VALUE
            "\"без номера\"",          // No digits at all
            "\"   \"",                 // Blank string
            "999999999999",            // Numeric literal exceeding Integer.MAX_VALUE
            "-2147483648"              // Numeric literal equal to Integer.MIN_VALUE
    })
    @DisplayName("Should return null ID safely on integer overflow or non-numeric values")
    void shouldReturnNullOnIdOverflowOrInvalidInput(String rawJsonValue) throws Exception {
        String json = String.format("{\"complex\": %s}", rawJsonValue);

        GSP_Data result = objectMapper.readValue(json, GSP_Data.class);

        assertNull(result.getId());
    }

    @ParameterizedTest
    @CsvSource({
            "'52,4345', 52.4345",
            "'  53.9006° N ', 53.9006",
            "'-27,5590', -27.5590",
            "'lat: 51.234', 51.234"
    })
    @DisplayName("Should normalize comma separators and extract coordinates from messy strings")
    void shouldNormalizeCoordinates(String rawCoord, double expected) throws Exception {
        String json = String.format("{\"latitude\": \"%s\", \"longitude\": \"%s\"}", rawCoord, rawCoord);

        GSP_Data result = objectMapper.readValue(json, GSP_Data.class);

        assertEquals(expected, result.getLatitude(), 0.00001);
        assertEquals(expected, result.getLongitude(), 0.00001);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"N/A\"",
            "\"\"",
            "\"NaN\"",
            "\"Infinity\"",
            "\"1e500\"", // Overflows Double.MAX_VALUE -> Infinity
            "null"
    })
    @DisplayName("Should return null coordinate when value is invalid, empty, or non-finite")
    void shouldReturnNullOnInvalidOrNonFiniteCoordinates(String rawCoordJson) throws Exception {
        String json = String.format("{\"latitude\": %s, \"longitude\": %s}", rawCoordJson, rawCoordJson);

        GSP_Data result = objectMapper.readValue(json, GSP_Data.class);

        assertNull(result.getLatitude());
        assertNull(result.getLongitude());
    }

    @Test
    @DisplayName("Should filter out null, blank, and nested items from fuels array")
    void shouldCleanFuelsArray() throws Exception {
        String json = """
                {
                  "complex": 1,
                  "fuels": ["АИ-92", null, "", "   ", "  ДТ Евро-5  ", {"nested": "ignored"}]
                }
                """;

        GSP_Data result = objectMapper.readValue(json, GSP_Data.class);

        assertEquals("АИ-92, ДТ Евро-5", result.getFuels());
    }

    @Test
    @DisplayName("Should safely handle empty JSON object and null text fields without exceptions")
    void shouldHandleEmptyObjectAndNullFields() throws Exception {
        String json = """
                {
                  "complex": null,
                  "latitude": null,
                  "longitude": null,
                  "mailaddress": null,
                  "district": ["unexpected_array"],
                  "fuels": null
                }
                """;

        GSP_Data result = objectMapper.readValue(json, GSP_Data.class);

        assertNotNull(result);
        assertNull(result.getId());
        assertNull(result.getLatitude());
        assertNull(result.getLongitude());
        assertEquals("", result.getAddress());
        assertEquals("", result.getDistrict());
        assertNull(result.getFuels());
    }
}
