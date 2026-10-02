# spola.codegen

Builders och with-metoder för records, som genererad och incheckad källkod. Ersätter Lomboks
`@Builder`, `@Builder(toBuilder = true)` och `@With` för records, utan annoteringar, utan
annotation processor och utan reflektion.

- Generatorn läser recordets **källfil** (Compiler Tree API, bara parsning) och skriver
  `<Record>With.java` bredvid den: ett `sealed interface` med en `withX` per komponent,
  `toBuilder()`, `builder()` och en nästlad `Builder`.
- Recordet väljer stödet explicit med `implements <Record>With`. Inget hittas genom
  namnkonventioner eller scanning; `PersonWith.builder()` går att följa med "Go to definition"
  till vanlig Java-kod.
- **Varje bygge kontrollerar varje genererad fil:** filen genereras på nytt i minnet och
  jämförs tecken för tecken med den incheckade. Har recordet ändrats, eller filen redigerats
  för hand, blir bygget rött med filens sökväg och kommandot `java Build.java codegen`, innan
  något annat kompileras.
- Den genererade koden beror bara på JDK:n och det egna recordet. Ingen modul behöver
  `requires spola.codegen` för att använda den.

| Lombok | Spola |
|---|---|
| `@Builder` | `PersonWith.builder().name("Ada").age(36).build()` |
| `@Builder(toBuilder = true)` | `person.toBuilder().name("Grace").build()` |
| `@With` | `person.withAge(37)` |
| `@Builder.Default` | en mall: `DEFAULTS.toBuilder().name("Ada").build()` |
| `@Singular`, obligatoriska fält, stegvisa builders | stöds inte |

## Arbetsflödet

Alla kommandon körs i `framework/`.

### 1. Generera

```
java Build.java codegen modules/demo/src/spola/demo/OrderLine.java OrderLine
```

Första argumentet är recordets källfil, det andra recordets namn i paketet. Ett nästlat record
anges med den yttre typen: `java Build.java codegen modules/demo/src/spola/demo/Summary.java
Summary.Rejection` skriver `RejectionWith.java`. Filen hamnar alltid i samma katalog (och
paket) som källfilen. Implementerar recordet inte gränssnittet ännu skrivs filen ändå, och
kommandot säger vad som fattas:

```
wrote modules/demo/src/spola/demo/OrderLineWith.java
note: record OrderLine does not implement OrderLineWith yet; add `implements OrderLineWith` to it (the build does not compile until it does)
```

En befintlig fil skrivs bara över om den är den genererade filen för just det recordet; en
handskriven `OrderLineWith.java` eller en genererad fil för ett annat record med samma enkla
namn lämnas orörd och kommandot avslutas med ett fel.

### 2. Implementera

```java
record OrderLine(int line, String sku, int quantity) implements OrderLineWith {
    OrderLine {
        Objects.requireNonNull(sku, "sku");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
    }
}
```

Gränssnittet är `sealed` med recordet som enda tillåtna typ, så ingen annan klass kan
implementera det och omvandlingen `(OrderLine) this` i den genererade koden är säker. Ett
generiskt record anger sina typparametrar i ordning: `record Pair<A, B extends Comparable<B>>(A
first, B second) implements PairWith<A, B>`.

### 3. Använda

```java
var order = OrderLineWith.builder().line(4).sku("apple").quantity(3).build();
var more = order.withQuantity(5);                 // nytt record, övriga komponenter oförändrade
var copy = order.toBuilder().sku("pear").build(); // builder förifylld med alla komponenter
```

Allt som returnerar ett record går genom den kanoniska konstruktorn, så den kompakta
konstruktorns kontroller körs alltid: `order.withQuantity(0)` och
`OrderLineWith.builder().build()` kastar precis som `new OrderLine(...)` gör. En komponent som
inte satts i buildern har Javas standardvärde (`null`, `0`, `false`). En builder kan
återanvändas; varje `build()` ger ett nytt record.

### 4. Ändra recordet och generera om

Läggs en komponent till, döps om eller byter typ blir nästa `java Build.java` rött, före
kompileringen:

```
generated files:
modules/demo/src/spola/demo/OrderLineWith.java: out of date: it no longer matches its record (the record changed, or this file was edited by hand); first difference at line 44: expected "     * Returns a copy of this record with {@code article} replaced.", found "     * Returns a copy of this record with {@code sku} replaced.". Regenerate it with: java Build.java codegen
1 generated file(s) do not match their records; regenerate with java Build.java codegen (in framework/)
BUILD FAILED: generated files do not match their records (see above); regenerate them with `java Build.java codegen` in framework/
```

Kör då

```
java Build.java codegen
```

som utan argument genererar om varje genererad fil i modulernas `src/` och `test/` och bara
skriver de filer vars innehåll ändras (på ett rent träd ändras ingenting). Samma meddelande
kommer när den genererade filen har redigerats för hand: ändringar görs i recordet, aldrig i
den genererade filen. Har recordet tagits bort tar du bort dess genererade fil.

## Lombok bredvid Spola

### `@Builder`

Lombok genererar buildern när klassen kompileras; den syns inte i källkoden.

```java
@Builder
record Person(String name, int age) { }

Person ada = Person.builder().name("Ada").age(36).build();
```

Spola skriver den som vanlig, incheckad Java i `PersonWith.java` (förkortat):

```java
// Generated by spola.codegen from Person.java, record Person. Do not edit.
// Regenerate after changing the record: java Build.java codegen (in framework/)

package com.example;

sealed interface PersonWith permits Person {

    static Builder builder() {
        return new Builder();
    }

    final class Builder {
        private String name;
        private int age;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder age(int age) {
            this.age = age;
            return this;
        }

        public Person build() {
            return new Person(this.name, this.age);
        }
    }
}
```

och anropet blir `PersonWith.builder()` i stället för `Person.builder()`, eftersom en statisk
metod i ett gränssnitt inte ärvs av recordet.

### `@Builder(toBuilder = true)`

```java
@Builder(toBuilder = true)
record Person(String name, int age) { }

Person grace = ada.toBuilder().name("Grace").build();
```

I Spola är `toBuilder()` en `default`-metod i gränssnittet, så anropet är detsamma:

```java
    default Builder toBuilder() {
        return new Builder((Person) this);
    }

    // i Builder:
    private Builder(Person from) {
        this.name = from.name();
        this.age = from.age();
    }
```

### `@With`

```java
@With
record Person(String name, int age) { }

Person older = ada.withAge(37);
```

I Spola, en `default`-metod per komponent:

```java
    default Person withAge(int age) {
        return toBuilder().age(age).build();
    }
```

Lomboks `@With` anropar också den kanoniska konstruktorn, så valideringen beter sig lika.

## Vad som stöds

- Toppnivårecords och nästlade records (statiska; i en klass, ett record, en enum eller ett
  gränssnitt) med paket- eller publik åtkomst. Gränssnittet blir publikt bara om recordet och
  alla typer runt det är publika, annars paketprivat. Ett privat record (eller ett record i en
  privat typ) går inte: gränssnittet bredvid kan inte nämna det.
- Generiska records: typparametrarna med sina gränser följer med till gränssnittet och till
  `Builder` (`PairWith<A, B extends Comparable<B>>`, `PairWith.Builder<A, B>`), utan varningar
  under `-Xlint:all -Werror`.
- Primitiva komponenter, arrayer, generiska typer (`Map<String, List<Integer>>`) och varargs.
  En varargs-komponent (`String... names`) tas som array i sättaren och with-metoden
  (`names(String[] names)`).
- Typer skrivs som i källan, i en fast form (ett mellanslag efter komma, inga annoteringar).
  Ett namn på en medlemstyp i recordet eller i en typ runt det kvalificeras
  (`Rejection` i `Summary` blir `Summary.Rejection`), eftersom gränssnittet ligger på
  toppnivå i paketet. Den genererade filen får källfilens `import`-deklarationer, inklusive
  `import module` och `import static`; en enkel import (`import java.util.Objects;`) som ingen
  komponenttyp använder tas bort, så att den genererade filen bara beror på det dess typer
  behöver.

## Fel

Generatorn stoppar med ett meddelande som namnger filen och recordet, utan stackspår, när
filen inte finns, inte är UTF-8 eller inte parsar; när recordet inte finns i filen eller typen
inte är ett record; när recordet är privat, saknar komponenter eller ligger i standardpaketet;
när en komponent heter `builder`, `toBuilder`, `build` eller `equals` (sättaren `equals(...)`
skulle krocka med `Object.equals`); när två komponenter ger samma with-metod (`url` och
`Url`); när recordet själv deklarerar `toBuilder()` eller en `withX` med en parameter; och när
en komponenttyp eller typparameter heter `Builder` (i gränssnittet betyder det den nästlade
`Builder`; skriv det fullständiga namnet).

```
modules/demo/src/spola/demo/Person.java: cannot generate PersonWith for record Person: the component build clashes with the generated method build(); rename the component
```

## Begränsningar

- Generatorn läser bara syntaxträdet. En medlemstyp som ärvs från en supertyp till recordet
  eller en typ runt det syns inte där och kvalificeras inte; skriv då typen med sitt
  kvalificerade namn i recordet.
- Den genererade filen känns igen enbart på rubriken (första raden). Tas rubriken bort är
  filen vanlig källkod som bygget inte längre kontrollerar.
- Filerna skrivs med radslutet `\n`. Ett utcheckningsverktyg som gör om radsluten till
  `\r\n` (git med `core.autocrlf=true`) gör att kontrollen ser filerna som ändrade.
