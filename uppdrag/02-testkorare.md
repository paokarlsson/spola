# Testkörare

## Mål
En egen testkörare som ersätter JUnit, eftersom JUnit kräver annoteringar och är
tredjepartskod. Tester registreras som lambdas i vanlig Java-kod. Från och med detta
uppdrag ska all kod i ramverket testas med denna körare.

## Bakgrund / kontext
Uppdrag 01 har levererat `Build.java` och modulen `guardrails`. Läs `framework/REGLER.md`,
`framework/PROGRESS.md` och `framework/ARCHITECTURE.md` innan du börjar.

## Krav
- Ny modul `spola.test` under `framework/modules/test/`.
- Tester definieras som värden, t.ex. `test("namn", () -> { ... })`, och grupperas i sviter
  som kan nästlas.
- Assertions: likhet, sanningsvärde, null, undantag (med typ och meddelande), samlingar
  (innehåll, ordning, storlek). Felmeddelanden visar förväntat och faktiskt värde.
- Fixtures: före/efter per test och per svit, med garanterad städning även när testet
  fallerar.
- Parametriserade tester: ett test körs mot en lista med indata och varje fall rapporteras
  separat.
- Timeout per test.
- Rapport till stdout: per test (ok/fel/hoppat över), sammanfattning med antal och tid, och
  stackspår för fel.
- Varje moduls testingång anges explicit i `Build.java` (modul → testklass med en metod som
  returnerar sviten). Ingen scanning, inga namnkonventioner.
- `Build.java` kör testerna för alla moduler efter guardrails och misslyckas om något test
  fallerar.
- Guardrails självtester från uppdrag 01 flyttas in i den nya köraren.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. Modulen `test` har egna tester som körs med köraren själv och som täcker assertions,
   fixtures, parametrisering, timeout och nästlade sviter.
3. Att lägga till ett test som fallerar i valfri modul ger rött bygge och rapporten visar
   testets namn, förväntat och faktiskt värde.
4. Ett test som kastar undantag efter att en fixture öppnat en resurs leder ändå till att
   städningen körs (verifieras av ett test).
5. Ett test som överskrider sin timeout markeras som fel och övriga tester körs vidare.
6. Ett parametriserat test med tre fall där ett fallerar rapporterar två ok och ett fel.
7. Guardrails självtester körs via den nya köraren.
8. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails grön).
9. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.

## Utanför scope
- Parallell körning av tester.
- Mocking-ramverk.
- IDE-integration.
- Rapportformat för CI (JUnit XML m.fl.).

## Begränsningar (valfritt)
Se `framework/REGLER.md`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.
