package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypstTemplateTest {

    record Person(String name, int age) {}
    record Header(String title) {}

    private static TypstEngine engine;

    @BeforeAll
    static void setUp() {
        engine = TypstEngine.builder().build();
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void renderWithMapData() {
        byte[] pdf = engine.template("map-test",
                        "#let data = json(\"data.json\")\n= Hello, #data.name!")
                .data("name", "World")
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "map-test");
    }

    @Test
    void renderWithRecordData() {
        byte[] pdf = engine.template("record-test",
                        "#let data = json(\"data.json\")\n= #data.name, age #str(data.age)")
                .data(new Person("Alice", 30))
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "record-test");
    }

    @Test
    void renderWithListData() {
        byte[] pdf = engine.template("list-test",
                        "#let data = json(\"data.json\")\n#for item in data.items [- #item\n]")
                .data("items", List.of("Alpha", "Beta", "Gamma"))
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "list-test");
    }

    @Test
    void renderWithNestedData() {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("city", "Springfield");
        address.put("street", "123 Main St");

        Map<String, Object> company = new LinkedHashMap<>();
        company.put("name", "Acme Corp");
        company.put("address", address);
        company.put("departments", List.of(
                Map.of("name", "Engineering", "headcount", 42),
                Map.of("name", "Sales", "headcount", 15)
        ));

        byte[] pdf = engine.template("nested-test",
                        "#let data = json(\"data.json\")\n" +
                        "#let company = data.company\n" +
                        "#let address = company.address\n" +
                        "= Company: #company.name\n" +
                        "Address: #address.city, #address.street\n" +
                        "#for dept in company.departments [\n" +
                        "  - #dept.name (#str(dept.headcount) employees)\n" +
                        "]")
                .data("company", company)
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "nested-test");
    }

    @Test
    void renderWithTableData() {
        List<Map<String, Object>> items = List.of(
                Map.of("name", "Widget", "qty", 5, "price", 9.99),
                Map.of("name", "Gadget", "qty", 3, "price", 24.50)
        );

        byte[] pdf = engine.template("table-test",
                        "#let data = json(\"data.json\")\n" +
                        "#let items = data.items\n" +
                        "= #data.title\n" +
                        "#table(\n" +
                        "  columns: (1fr, auto, auto),\n" +
                        "  table.header([*Name*], [*Qty*], [*Price*]),\n" +
                        "  ..items.map(item => (item.name, str(item.qty), str(item.price))).flatten()\n" +
                        ")")
                .data("title", "Inventory")
                .data("items", items)
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "table-test");
    }

    @Test
    void renderWithDateTypes() {
        LocalDate today = LocalDate.of(2025, 6, 15);
        byte[] pdf = engine.template("date-test",
                        "#let data = json(\"data.json\")\n= Report for #data.date")
                .data("date", today)
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "date-test");
    }

    @Test
    void renderWithBigDecimal() {
        byte[] pdf = engine.template("bigdecimal-test",
                        "#let data = json(\"data.json\")\n= Total: #data.amount")
                .data("amount", new BigDecimal("12345.6789"))
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "bigdecimal-test");
    }

    @Test
    void renderWithRawJson() {
        byte[] pdf = engine.template("rawjson-test",
                        "#let data = json(\"data.json\")\n= Value: #str(data.x)")
                .dataJson("{\"x\":42}")
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "rawjson-test");
    }

    @Test
    void renderWithCombinedData() {
        byte[] pdf = engine.template("combined-test",
                        "#let data = json(\"data.json\")\n= #data.title\nBy #data.author")
                .data(new Header("My Report"))
                .data("author", "Jane Doe")
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "combined-test");
    }

    @Test
    void renderWithoutDataJson() {
        byte[] pdf = engine.template("static-test", "= Static Document\nNo data needed.")
                .renderPdf();
        PdfAssert.assertPdfMatchesReference(pdf, "static-test");
    }
}
