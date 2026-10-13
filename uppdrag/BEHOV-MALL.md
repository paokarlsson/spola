# <Kort namn på behovet>

<!--
Mall för behov från ett projekt som använder Spola. Spara som
uppdrag/behov/<ÅÅÅÅ-MM-DD>-<namn>.md. Planeraren prövar behovet mot regeln i
framework/MONSTER.md ("Hör det hemma i Spola?") och gör om det till ett uppdrag
eller avvisar det med en motivering. Håll det kort: planeraren skriver själva uppdraget.
-->

Projekt: <appens namn och repo>
Spola-version: <versionen appen använder, t.ex. 0.1.0>

## Behov
Vad appen behöver göra och varför. 2–4 meningar.

## Motsvarighet i Spring eller Lombok
Vad en Spring-app hade använt, t.ex. `JavaMailSender` i Spring Mail eller `@Scheduled`.

## Användningsfall
Koden appen vill kunna skriva, så konkret som möjligt. Den blir ett acceptanskriterium.

```java
var mailer = Mailer.smtp(config.section("mail"));
mailer.send(Mail.to(user.email()).subject("Välkommen").text(body));
```

## Krav som inte syns i koden (valfritt)
Gränser, fel, säkerhet, prestanda. T.ex. "STARTTLS krävs", "ska klara 100 jobb per sekund".

## Lokal lösning i appen (valfritt)
Var appens tillfälliga lösning finns, så att den kan bytas ut när modulen är släppt.
