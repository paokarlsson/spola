# spola.di

Dependency injection utan annoteringar, scanning eller reflektion. Ersätter Spring Core
(`ApplicationContext`, `@Component`, `@Autowired`, `@Qualifier`, `@Scope`, `@PreDestroy`).

- Komponenter binds explicit i Java-kod: en nyckel (`Key<T>`, typ plus valfri kvalificerare)
  och en fabrik som får sina beroenden.
- Varje bindning deklarerar sina beroenden, så `Container.create` kan kontrollera hela grafen
  innan något skapas: saknade beroenden, cykler (`A -> B -> C -> A`), dubbla bindningar och
  singletons som skulle fånga ett anropsscopat objekt. Alla fel rapporteras på en gång, med
  platsen där bindningen gjordes.
- Scopes: `SINGLETON` (lat, standard), `EAGER_SINGLETON`, `PROTOTYPE` och `CALL`
  (byggt på `ScopedValue`).
- `Container.close()` stänger de `AutoCloseable`-singletons containern skapade, i omvänd
  skapandeordning. Ett fel stoppar inte stängningen av övriga; alla samlas i en
  `CloseException`.

## Exempel bredvid Spring

En betaltjänst med två implementationer av samma gränssnitt, som skiljs åt med en
kvalificerare, och en orderservice som behöver ett repository och en av dem.

### Spring

Med Spring hittas klasserna genom classpath-scanning, beroendena läses från konstruktorn med
reflektion och kvalificeraren är en annotering. Ett saknat beroende eller en cykel märks när
kontexten startar, och felet pekar på en klass, inte på var kopplingen gjordes.

```java
@Component
@Qualifier("eu")
class EuGateway implements PaymentGateway { ... }

@Component
@Qualifier("us")
class UsGateway implements PaymentGateway { ... }

@Component
class JdbcOrderRepo implements OrderRepo {
    @Autowired
    JdbcOrderRepo(DataSource dataSource) { ... }
}

@Component
class OrderService {
    private final OrderRepo repo;
    private final PaymentGateway gateway;

    @Autowired
    OrderService(OrderRepo repo, @Qualifier("eu") PaymentGateway gateway) {
        this.repo = repo;
        this.gateway = gateway;
    }
}

// någonstans i uppstarten
var context = new AnnotationConfigApplicationContext("shop");
var service = context.getBean(OrderService.class);
```

### Spola

Med Spola står samma koppling i vanlig Java-kod. Kvalificeraren är ett vanligt värde (här en
enum), och "Go to definition" på `OrderService::new` leder till konstruktorn. Klasserna själva
har inga annoteringar och vet inget om containern.

```java
enum Region { EU, US }

class EuGateway implements PaymentGateway { ... }
class UsGateway implements PaymentGateway { ... }
class JdbcOrderRepo implements OrderRepo {
    JdbcOrderRepo(DataSource dataSource) { ... }
}
record OrderService(OrderRepo repo, PaymentGateway gateway) { ... }

ContainerModule payments = binder -> {
    binder.bind(Key.of(PaymentGateway.class, Region.EU)).to(EuGateway::new);
    binder.bind(Key.of(PaymentGateway.class, Region.US)).to(UsGateway::new);
};

ContainerModule orders = binder -> {
    binder.install(payments);
    binder.bind(DataSource.class).to(Config.class, PooledDataSource::new).in(Scope.EAGER_SINGLETON);
    binder.bind(OrderRepo.class).to(DataSource.class, JdbcOrderRepo::new);
    binder.bind(OrderService.class).to(
            Key.of(OrderRepo.class), Key.of(PaymentGateway.class, Region.EU), OrderService::new);
};

try (var container = Container.create(orders, configModule)) {
    var service = container.get(OrderService.class);
    ...
}
```

Saknas `OrderRepo` blir felet från `Container.create`, innan något har skapats:

```text
spola.di.WiringException: the container has 1 wiring problem:
  - shop.OrderService (bound at shop.Modules.lambda$orders$1(Modules.java:14)) depends on shop.OrderRepo, which is not bound
```

### Motsvarigheter

| Spring | Spola |
|---|---|
| `@Component` på klassen | `binder.bind(Typ.class).to(...)` i en `ContainerModule` |
| `@Autowired` på konstruktorn | beroendena i `to(A.class, B.class, Typ::new)`; kompilatorn kontrollerar att de passar konstruktorn |
| `@Qualifier("eu")` | `Key.of(Typ.class, Region.EU)`: ett vanligt värde, inte en annotering |
| `@Scope("prototype")` | `.in(Scope.PROTOTYPE)` |
| singleton (Springs standard, skapas vid start) | `.in(Scope.EAGER_SINGLETON)` |
| `@Lazy` singleton | standard (`Scope.SINGLETON`), skapas vid första `get` |
| request scope | `.in(Scope.CALL)` och `container.runInCallScope(...)` |
| `@Configuration`-klass | `ContainerModule`, installeras med `Container.create` eller `binder.install` |
| `@PreDestroy` / `DisposableBean` | `AutoCloseable`, stängs av `container.close()` i omvänd skapandeordning |
| `ObjectProvider` / `getBean` i en bean | finns inte: en fabrik får bara sina deklarerade beroenden |

## Två sätt att deklarera beroenden

**Typad form** (föredras): beroendena listas som typer eller nycklar och fabriken får dem som
argument. Det deklarerade och det använda är samma sak, och kompilatorn kontrollerar typerna.
Upp till fem beroenden.

**Resolverform**: för fler beroenden, eller en fabrik som väljer vad den använder i runtime.
Beroendena deklareras med `Dependencies`, och fabriken får en `Resolver` som bara lämnar ut de
deklarerade nycklarna, och bara medan fabriken körs:

```java
binder.bind(OrderService.class).to(Dependencies.of(OrderRepo.class),
        r -> new OrderService(r.get(OrderRepo.class)));
```

Ber fabriken om något den inte deklarerat blir det ett `ResolutionException` med det
deklarerade i meddelandet, första gången komponenten skapas. Motiveringen står i
`framework/ARCHITECTURE.md` ("DI-containern").

## Anropsscope

```java
binder.bind(RequestContext.class).to(RequestContext::new).in(Scope.CALL);

container.runInCallScope(() -> {
    var ctx = container.get(RequestContext.class);   // samma instans i hela anropet
    handle(ctx);
});                                                  // stängs här om den är AutoCloseable
```

Utanför ett anropsscope ger en `CALL`-komponent fel. En singleton får inte bero på en
`CALL`-komponent, inte heller via en prototype; det upptäcks av `Container.create`.
