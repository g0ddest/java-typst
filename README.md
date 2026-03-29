# typst-java

[Typst](https://typst.app) PDF generation library for Java 25+.

Renders Typst templates into PDF documents via an embedded native Typst compiler called through [Java FFM API](https://openjdk.org/jeps/454) (Project Panama). Zero external runtime dependencies – one JAR, everything bundled.

## Features

- **Embedded Typst compiler** — no need to install Typst or any other tool
- **Java FFM API** (Project Panama) — no JNI, no subprocess, direct in-process calls
- **Fluent builder API** — configure engine, bind data, render PDF in a few lines
- **Template engine** — pure Typst templates with JSON data injection via virtual filesystem
- **Auto-serialization** — Java Records, POJOs, Maps, Lists automatically serialized to JSON
- **Custom fonts** — load from directories, byte arrays, InputStreams (classpath resources, DB, S3)
- **Template caching** — compiled templates reused across renders, mtime-based invalidation
- **Thread-safe** — one engine instance, concurrent rendering from multiple threads
- **Structured errors** — `TypstCompilationException` with file, line, column, message, hints
- **Cross-platform** — Linux, macOS, Windows; x86_64 and aarch64

## Quick Start

### Maven

```xml
<dependency>
    <groupId>name.velikodniy.vitaliy</groupId>
    <artifactId>typst-java</artifactId>
    <version>${add here the last version}</version>
</dependency>
```

### Basic Usage

```java
try (var engine = TypstEngine.builder().build()) {
    byte[] pdf = engine.template("hello", "= Hello, World!")
        .renderPdf();
    Files.write(Path.of("hello.pdf"), pdf);
}
```

### Templates with Data

Create a Typst template `invoice.typ`:

```typst
#let data = json("data.json")

= Invoice ##data.number

*Customer:* #data.customer.name \
*Date:* #data.date

#table(
  columns: (1fr, auto, auto),
  table.header([*Item*], [*Qty*], [*Price*]),
  ..data.items.map(item => (
    item.name,
    str(item.qty),
    str(item.price),
  )).flatten()
)

*Total:* #data.total
```

Render it from Java:

```java
record Customer(String name, String email) {}
record LineItem(String name, int qty, BigDecimal price) {}

try (var engine = TypstEngine.builder()
        .addFont(getClass().getResourceAsStream("/fonts/corporate.ttf"))
        .build()) {

    byte[] pdf = engine.template(Path.of("invoice.typ"))
        .data("number", "INV-2026-001")
        .data("customer", new Customer("Acme Corp", "billing@acme.com"))
        .data("date", LocalDate.now())
        .data("items", List.of(
            new LineItem("Consulting", 40, new BigDecimal("150.00")),
            new LineItem("Development", 120, new BigDecimal("200.00"))
        ))
        .data("total", "$30,000.00")
        .renderPdf();
}
```

### Record Auto-Mapping

```java
record InvoiceData(
    String number,
    Customer customer,
    LocalDate date,
    List<LineItem> items
) {}

byte[] pdf = engine.template(Path.of("invoice.typ"))
    .data(new InvoiceData("INV-001", customer, LocalDate.now(), items))
    .renderPdf();
```

Record fields become top-level keys in `data.json`. You can combine `.data(record)` with `.data(key, value)` — last write wins on key conflicts.

## API Reference

### TypstEngine

```java
TypstEngine engine = TypstEngine.builder()
    .addFontDir(Path.of("/usr/share/fonts"))     // directory with .ttf/.otf files
    .addFont(fontBytes)                           // byte[]
    .addFont(inputStream)                         // InputStream (classpath, DB, S3)
    .enableTemplateCache(true)                    // default: true
    .build();
```

- Thread-safe, create once, share across threads
- Implements `AutoCloseable` — use try-with-resources
- Default fonts from Typst are always available (bundled in native library)

### TypstTemplate

```java
// From file
engine.template(Path.of("template.typ"))

// From string (name used as cache key)
engine.template("my-template", typstSource)
```

#### Data Binding

```java
.data("key", value)           // key-value pair
.data(record)                 // expand record fields as top-level keys
.dataJson("{\"raw\":true}")   // raw JSON string
.renderPdf()                  // returns byte[]
```

### Cache Management

```java
engine.invalidateTemplate(Path.of("template.typ"));
engine.invalidateTemplate("cache-key");
engine.invalidateAllTemplates();
```

File templates auto-invalidate when mtime changes.

### Error Handling

```java
try {
    engine.template(path).data(data).renderPdf();
} catch (TypstCompilationException e) {
    for (TypstDiagnostic d : e.getDiagnostics()) {
        System.err.printf("%s:%d:%d: %s %s%n",
            d.file(), d.line(), d.column(),
            d.severity(), d.message());
        // e.g.: "invoice.typ:12:5: ERROR unknown variable: compny"
    }
}
```

Exception hierarchy:

| Exception | When |
|—-|—-|
| `TypstCompilationException` | Template compilation errors (with structured diagnostics) |
| `TypstEngineException` | Configuration errors (bad font, missing directory, closed engine) |
| `TypstNativeException` | Native library loading or FFI call failures |

### Data Type Mapping

| Java Type | JSON Representation |
|—-|—-|
| `String` | `"string"` |
| `int`, `long`, `double` | number literal |
| `boolean` | `true` / `false` |
| `BigDecimal` | `"string"` (preserves precision) |
| `LocalDate` | `"2026-03-29"` |
| `LocalDateTime` | `"2026-03-29T10:30:00"` |
| `Enum` | `"NAME"` |
| `Record` | object (component names as keys) |
| `Map<String, ?>` | object |
| `List<?>` / array | array |
| POJO | object (via getters) |
| `null` | `null` |

## Writing Templates

Templates are standard Typst files. Data is injected via a virtual `data.json` file:

```typst
#let data = json("data.json")

// Access fields
#data.name
#data.customer.email

// Iterate arrays
#for item in data.items [
  - #item.name: #str(item.qty) x #str(item.price)
]

// Tables
#table(
  columns: (auto, auto),
  ..data.rows.map(r => (r.key, r.value)).flatten()
)

// Conditionals
#if data.showHeader [
  = #data.title
]
```

Templates work in [typst.app](https://typst.app) with a manually provided `data.json` — no vendor lock-in.

Typst packages from [packages.typst.org](https://packages.typst.org) are supported and downloaded on demand.

## Architecture

```
Java Application
    |
    v
TypstEngine (fluent API, AutoCloseable)
    |
    v
TypstTemplate (data binding, serialization)
    |
    v
TypstNative (Java FFM API bindings)
    |  C ABI calls via MemorySegment + MethodHandle
    v
libtypst_java.so/dylib/dll (Rust shared library)
    |
    |—- Typst compiler (typst 0.13)
    |—- PDF exporter (typst-pdf 0.13)
    |—- Font book (typst-assets, embedded)
    |—- Template cache (RwLock<HashMap>)
    |—- Virtual filesystem (data.json injection)
    |—- Package resolver (packages.typst.org)
```

## Building from Source

### Prerequisites

- Java 25+
- Rust toolchain (stable)
- Maven 3.9+

### Build

```bash
mvn clean verify
```

This will:
1. Compile the Rust native library (`cargo build —release`)
2. Copy it to the classpath
3. Run Rust tests (`cargo test`)
4. Compile Java sources
5. Run Java tests (63 tests)

## Requirements

- **Java 25+** (uses stable FFM API from JEP 454)
- **No Rust needed at runtime** — native library is bundled in the JAR
- **No Typst installation needed** — compiler is embedded

## Supported Platforms

| OS | Architecture | Status |
|—-|—-|—-|
| Linux | x86_64 | Supported |
| Linux | aarch64 | Supported |
| macOS | x86_64 | Supported |
| macOS | aarch64 (Apple Silicon) | Supported |
| Windows | x86_64 | Supported |

## License

[Apache License 2.0](LICENSE)
