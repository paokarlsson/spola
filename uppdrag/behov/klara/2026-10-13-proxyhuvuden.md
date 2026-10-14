# Rätt schema och klientadress bakom en omvänd proxy (X-Forwarded-*)

Projekt: psykologen (paokarlsson/psykologen), backend i Spring Boot som ska kunna gå över till Spola
Spola-version: 0.1.0

## Behov
I produktion står Caddy framför backenden. Caddy avslutar TLS och skickar vidare med vanlig
HTTP över Dockers interna nät, med `X-Forwarded-Proto`, `X-Forwarded-For` och
`X-Forwarded-Host`. Appen behöver veta att användaren egentligen är på `https` och vem
klienten är, men bara när förfrågan kommer från en betrodd proxy. Annars kan vem som helst som
når porten direkt låtsas vara på `https` eller förfalska sin adress. I Spring sköts det av en
rad i `application.properties`. Spola har inget motsvarande i dag.

## Motsvarighet i Spring eller Lombok
Spring Boot: `server.forward-headers-strategy=native` (Tomcats `RemoteIpValve`, som bara litar
på interna adresser som standard) eller `framework` (`ForwardedHeaderFilter`, som också läser
RFC 7239 `Forwarded`). Därefter ger `request.isSecure()`, `getScheme()` och `getRemoteAddr()`
klientens värden.

## Användningsfall
```properties
# application.properties i Spring i dag
server.forward-headers-strategy=native
```

```java
// Med Spola (namnen är förslag, planeraren bestämmer)
var router = Router.builder()
        .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))  // 10/8, 172.16/12, 192.168/16, loopback, fc00::/7
        .get("/api/whoami", request -> Response.text(200,
                request.scheme() + " " + request.clientAddress().getHostAddress()))
        .build();
// Bakom Caddy:          "https 203.0.113.7"
// Direkt utan proxy:    "http 127.0.0.1"
// Utifrån med falska huvuden till en server som inte litar på avsändaren: huvudena ignoreras
```

Filter som körs efter, till exempel säkerhetsfiltret och en CSRF-cookie med `Secure` efter
schemat, ska se klientens schema och adress.

## Krav som inte syns i koden (valfritt)
- Huvudena används bara när den direkta avsändaren är betrodd. Annars ignoreras de helt.
- `X-Forwarded-For` med flera adresser läses från höger, och betrodda proxyadresser hoppas
  över, som `RemoteIpValve` gör. Ogiltiga adresser och värden ger ett tydligt utfall
  (ignoreras eller ger 400, bestäm och dokumentera), aldrig ett undantag som blir 500.
- `X-Forwarded-Proto` får bara vara `http` eller `https`.
- Säkerhetskritiskt: huvudena är data utifrån.

## Lokal lösning i appen (valfritt)
Ingen. Appen använder Spring Boots inställning.
