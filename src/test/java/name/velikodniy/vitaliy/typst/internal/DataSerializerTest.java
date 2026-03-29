package name.velikodniy.vitaliy.typst.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DataSerializerTest {

    @Nested
    class PrimitiveTypes {
        @Test void serializesString() { assertEquals("\"hello\"", DataSerializer.toJson("hello")); }
        @Test void serializesStringWithEscaping() {
            assertEquals("\"he\\\"llo\"", DataSerializer.toJson("he\"llo"));
            assertEquals("\"back\\\\slash\"", DataSerializer.toJson("back\\slash"));
            assertEquals("\"new\\nline\"", DataSerializer.toJson("new\nline"));
            assertEquals("\"tab\\there\"", DataSerializer.toJson("tab\there"));
        }
        @Test void serializesInteger() { assertEquals("42", DataSerializer.toJson(42)); }
        @Test void serializesLong() { assertEquals("9999999999", DataSerializer.toJson(9999999999L)); }
        @Test void serializesDouble() { assertEquals("3.14", DataSerializer.toJson(3.14)); }
        @Test void serializesBoolean() { assertEquals("true", DataSerializer.toJson(true)); assertEquals("false", DataSerializer.toJson(false)); }
        @Test void serializesNull() { assertEquals("null", DataSerializer.toJson(null)); }
        @Test void serializesBigDecimalAsString() { assertEquals("\"123.456789\"", DataSerializer.toJson(new BigDecimal("123.456789"))); }
    }

    @Nested
    class DateTypes {
        @Test void serializesLocalDate() { assertEquals("\"2026-03-29\"", DataSerializer.toJson(LocalDate.of(2026, 3, 29))); }
        @Test void serializesLocalDateTime() { assertEquals("\"2026-03-29T10:30:00\"", DataSerializer.toJson(LocalDateTime.of(2026, 3, 29, 10, 30, 0))); }
    }

    @Nested
    class Collections {
        @Test void serializesList() { assertEquals("[1,2,3]", DataSerializer.toJson(List.of(1, 2, 3))); }
        @Test void serializesEmptyList() { assertEquals("[]", DataSerializer.toJson(List.of())); }
        @Test void serializesArray() { assertEquals("[\"a\",\"b\"]", DataSerializer.toJson(new String[]{"a", "b"})); }
        @Test void serializesMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("name", "test"); map.put("value", 42);
            assertEquals("{\"name\":\"test\",\"value\":42}", DataSerializer.toJson(map));
        }
        @Test void serializesEmptyMap() { assertEquals("{}", DataSerializer.toJson(Map.of())); }
        @Test void serializesNestedStructures() {
            var inner = Map.of("x", 1);
            var outer = new LinkedHashMap<String, Object>();
            outer.put("nested", inner); outer.put("list", List.of("a", "b"));
            String json = DataSerializer.toJson(outer);
            assertTrue(json.contains("\"nested\":{\"x\":1}"));
            assertTrue(json.contains("\"list\":[\"a\",\"b\"]"));
        }
    }

    @Nested
    class Enums {
        enum Color { RED, GREEN, BLUE }
        @Test void serializesEnumAsString() { assertEquals("\"RED\"", DataSerializer.toJson(Color.RED)); }
    }

    @Nested
    class Records {
        record Point(int x, int y) {}
        record Named(String name, Point location) {}
        @Test void serializesRecord() { assertEquals("{\"x\":1,\"y\":2}", DataSerializer.toJson(new Point(1, 2))); }
        @Test void serializesNestedRecord() {
            assertEquals("{\"name\":\"origin\",\"location\":{\"x\":0,\"y\":0}}", DataSerializer.toJson(new Named("origin", new Point(0, 0))));
        }
    }

    @Nested
    class Pojos {
        @Test void serializesPojoViaGetters() {
            var pojo = new TestPojo("hello", 42);
            String json = DataSerializer.toJson(pojo);
            assertTrue(json.contains("\"name\":\"hello\""));
            assertTrue(json.contains("\"value\":42"));
        }
        @Test void serializesPojoWithNullGetter() {
            var pojo = new TestPojo(null, 42);
            String json = DataSerializer.toJson(pojo);
            assertTrue(json.contains("\"name\":null"));
            assertTrue(json.contains("\"value\":42"));
        }
    }

    public static class TestPojo {
        private final String name; private final int value;
        public TestPojo(String name, int value) { this.name = name; this.value = value; }
        public String getName() { return name; }
        public int getValue() { return value; }
    }

    @Nested
    class MergeApi {
        record Info(String name) {}
        @Test void mergesKeyValuePairs() {
            var b = new DataSerializer.Builder(); b.put("name", "test"); b.put("count", 5);
            assertEquals("{\"name\":\"test\",\"count\":5}", b.toJson());
        }
        @Test void mergesRecordFields() {
            var b = new DataSerializer.Builder(); b.putRecord(new Info("hello"));
            assertEquals("{\"name\":\"hello\"}", b.toJson());
        }
        @Test void mergesKeyValueAndRecord() {
            var b = new DataSerializer.Builder(); b.put("extra", true); b.putRecord(new Info("hello"));
            String json = b.toJson();
            assertTrue(json.contains("\"extra\":true")); assertTrue(json.contains("\"name\":\"hello\""));
        }
        @Test void lastWriteWinsOnConflict() {
            var b = new DataSerializer.Builder(); b.put("name", "first"); b.put("name", "second");
            assertEquals("{\"name\":\"second\"}", b.toJson());
        }
        @Test void mergesRawJson() {
            var b = new DataSerializer.Builder(); b.putRawJson("{\"a\":1,\"b\":2}"); b.put("c", 3);
            String json = b.toJson();
            assertTrue(json.contains("\"a\":1")); assertTrue(json.contains("\"b\":2")); assertTrue(json.contains("\"c\":3"));
        }
        @Test void mergesRawJsonWithEscapes() {
            var b = new DataSerializer.Builder();
            b.putRawJson("{\"msg\":\"hello\\nworld\",\"path\":\"c:\\\\dir\"}");
            String json = b.toJson();
            assertTrue(json.contains("\"msg\":\"hello\\nworld\""));
            assertTrue(json.contains("\"path\":\"c:\\\\dir\""));
        }
    }

    @Nested
    class UnicodeAndEdgeCases {
        @Test void serializesUnicode() { assertEquals("\"Привет мир\"", DataSerializer.toJson("Привет мир")); }
        @Test void serializesControlCharacters() {
            String json = DataSerializer.toJson("a\u0000b\u001Fc");
            assertTrue(json.contains("\\u0000")); assertTrue(json.contains("\\u001f"));
        }
        @Test void serializesEmptyString() { assertEquals("\"\"", DataSerializer.toJson("")); }
        @Test void handlesNullInList() {
            var list = new ArrayList<String>(); list.add("a"); list.add(null); list.add("b");
            assertEquals("[\"a\",null,\"b\"]", DataSerializer.toJson(list));
        }
        @Test void handlesNullValueInMap() {
            var map = new LinkedHashMap<String, Object>(); map.put("key", null);
            assertEquals("{\"key\":null}", DataSerializer.toJson(map));
        }
    }
}
