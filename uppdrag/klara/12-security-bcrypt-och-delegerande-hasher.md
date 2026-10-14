# Lösenord från Spring: BCrypt och delegerande hasher med `{id}`-prefix (`spola.security`)

## Mål
`spola.security` ska kunna verifiera och skapa BCrypt-hashar i Spring Securitys format och välja
algoritm efter ett `{id}`-prefix, som Spring Securitys `BCryptPasswordEncoder` och
`DelegatingPasswordEncoder`. En app som går över från Spring ska kunna behålla sina lagrade
hashar (`{bcrypt}$2a$10$...`) utan att användarna får nya lösenord, och en hash som Spola skriver
ska gå att använda i Spring igen om appen behöver backa. Användare med en hash från en annan
algoritm eller med lägre kostnad flyttas till standardinställningarna av den befintliga
uppgraderingen i `Authenticator`. Uppdraget täcker hela behovet "BCrypt-hashar och delegerande
lösenordshasher" från appen psykologen. Koden är säkerhetskritisk: PROGRESS.md ska säga att en
människa bör granska den innan ramverket används på riktigt.

## Bakgrund / kontext
Uppdrag 01–10 och 11a–11c är klara (se `uppdrag/klara/` och `framework/PROGRESS.md`).
Behov: `uppdrag/behov/klara/2026-10-13-losenord-bcrypt.md`.

Läs `framework/REGLER.md`, `framework/MONSTER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnittet "Säkerhet del 1" (10a). Alla
tester skrivs med `spola.test`.

Det som finns i `spola.security` (10a) och som uppdraget bygger på:
- `PasswordHasher` (`hash`, `matches`, `needsRehash`) med kontraktet: `null` ger
  `NullPointerException`; ett lösenord som implementationen inte tar ger
  `IllegalArgumentException` i `hash` och `false` i `matches`; en felaktig lagrad hash ger
  `IllegalArgumentException` vars meddelande inte innehåller strängen.
- `Pbkdf2PasswordHasher`: PHC-format, strikt tolkning, UTF-8 med `CodingErrorAction.REPORT` och
  utan normalisering, nollställda byte-arrayer, `new SecureRandom()` per hasher och
  `MessageDigest.isEqual`.
- `Authenticator.of(store, hasher[, upgrade])`: dummyhashen görs med `hasher.hash` när
  autentiseraren skapas. Uppgraderingen sker när `needsRehash` är `true`, och ett
  `RuntimeException` från `hasher.hash` eller `upgrade` loggas på `WARNING` med bara klassnamnet
  medan inloggningen ändå lyckas. `InMemoryUserStore.upgrader(hasher)` byter atomärt en hash bara
  om den fortfarande `needsRehash`.
- Modulen har inga `requires`. `SourceRulesTests` fäster antalet huvudkällfiler (13) och en lista
  med förbjudna strängar. `ReadmeTests` fäster avvikelseraderna i `README.md`, bland dem "Bara
  PBKDF2-SHA256", som blir fel med det här uppdraget.
- Har 10a levererat andra namn än de som står här gäller de levererade (se PROGRESS.md och
  ARCHITECTURE.md).

Behovets användningsfall ska fungera som det står:

```java
// Verifiera hashar som Spring skrev, och skapa nya i samma format.
PasswordHasher bcrypt = BCryptPasswordHasher.defaults();          // kostnad 10, $2a$ som Spring
PasswordHasher hasher = PasswordHasher.delegating("bcrypt", Map.of(
        "bcrypt", bcrypt,
        "pbkdf2", Pbkdf2PasswordHasher.defaults()));

hasher.matches("hemligt", storedFromSpring);                     // "{bcrypt}$2a$10$...", som i Spring
String stored = hasher.hash("hemligt");                          // "{bcrypt}$2a$10$..."

var users = new InMemoryUserStore();
for (var account : accounts) {                                    // från APP_AUTH_USERS_<n>_*
    users.add(new User(account.username(), account.passwordHash(), Set.of("USER"), true));
}
var authenticator = Authenticator.of(users, hasher);
```

Behovets acceptans: en hash från Springs `BCryptPasswordEncoder` verifieras (utan prefix med
`BCryptPasswordHasher`, med `{bcrypt}`-prefix med den delegerande hasharen), och en hash från
Spola godtas av Springs `BCryptPasswordEncoder.matches`. Det testas mot publicerade testvektorer,
inte bara mot Spolas egen `hash`.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)

**Var och vad.**
- Allt hamnar i `spola.security`, paketet `spola.security`. Ingen ny modul, inga `requires`, inga
  ändringar i andra moduler och inga ändringar i `Build.java`.
- Publikt tillkommer klassen `BCryptPasswordHasher` och den statiska metoden
  `PasswordHasher.delegating(String defaultId, Map<String, ? extends PasswordHasher> hashers)`.
  Bcrypt-algoritmen (Blowfish, EksBlowfish, bcrypts Base64) och den delegerande implementationen
  är paketprivata. Hela beteendet för `delegating` dokumenteras i metodens Javadoc.
- Motivering: till skillnad från `Pbkdf2.deriveSha256` är en publik bcrypt-primitiv lätt att
  använda fel. Den kortar tyst lösenord över 72 byte, och det är just CVE-2025-22228. Därför
  ligger reglerna i `BCryptPasswordHasher`, och bara den är publik.

**Varför bcrypt implementeras i Spola (REGLER §4).**
- JDK:n har ingen bcrypt, och dess `Cipher` för `Blowfish` går inte att använda. Bcrypts dyra
  nyckelschema (EksBlowfishSetup) ändrar P-arrayen och S-boxarna direkt, och det tillståndet går
  inte att ge en JDK-cipher.
- Algoritmen implementeras därför med heltalsaritmetik efter Provos och Mazières, "A
  Future-Adaptable Password Scheme" (USENIX 1999). OpenBSD:s `bcrypt.c` är referens för formatet.
- Allt annat bygger på JDK:ns primitiver: `SecureRandom` för saltet, `MessageDigest.isEqual` för
  jämförelsen och `CharsetEncoder` för UTF-8.
- Korrektheten visas mot publicerade testvektorer (jBCrypt/OpenBSD och Openwall), mot JDK:ns egen
  Blowfish och mot vektorer från en oberoende implementation (libxcrypt). Se kriterium 4–6.

**Konstanterna härleds från π i koden.**
- Blowfishs initiala P-array (18 ord) och S-boxar (4 × 256 ord) är de första 33 344 bitarna
  (8 336 hexsiffror) av π:s bråkdel.
- De räknas fram en gång per JVM, i en statisk hållare och alltså vid första användningen, med
  `BigInteger`. Ett exempel är Machins formel `π = 16·arctan(1/5) − 4·arctan(1/239)` i fixpunkt
  med minst 64 skyddsbitar. Inga tabeller kopieras in från en annan implementation (REGLER §1:
  ingen inkopierad källkod).
- Varje hash arbetar på en egen kopia, och de härledda tabellerna ändras aldrig.
- Under planeringen gav metoden `P[0]=243F6A88`, `P[17]=8979FB1B`, `S0[0]=D1310BA6`,
  `S0[255]=6E85076A`, `S1[0]=4B7A70E9`, `S2[0]=E93D5A68`, `S3[0]=3A39CE37` och
  `S3[255]=3AC372E6`, vilket stämmer med Blowfish-specifikationen.

**Algoritmens semantik, som jBCrypt (Springs `BCrypt`) och OpenBSD:s `$2b$`.**
- Nyckeln är lösenordets byte följda av en NUL-byte, upprepade cykliskt; varje expansion läser 72
  byte. Saltet är 16 byte. Texten `OrpheanBeholderScryDoubt` krypteras 64 gånger i ECB, och de
  första 23 av de 24 byten kodas.
- Kostnaden `c` ger `2^c` varv. Antalet räknas som `long`, eller med en loop som klarar `2^31`.
  Annars blir kostnad 31 noll varv eller ett negativt `int`, eftersom `1 << 31` är negativt i
  Java.
- `$2a$`, `$2b$` och `$2y$` räknas exakt likadant, som i jBCrypt. Det skiljer sig från två andra
  implementationer, och ingen av skillnaderna kan nå `BCryptPasswordHasher`, som bara tar 1–72
  byte giltig UTF-8:
  - OpenBSD:s gamla `$2a$`: bara för lösenord över 255 byte;
  - motåtgärden i crypt_blowfish/libxcrypt:s `$2a$`: bara för byte-strängar som inte är giltig
    UTF-8, till exempel `\xff\xff\xa3`. libxcrypt ger där
    `$2a$05$/OK.fbVrR/bpIqNJ5ianF.nqd1wy.pTMdcvrRWxyiGL2eMz.2a85.`, medan jBCrypt och Spola ger
    samma hashdel som för `$2b$`.
- `$2x$` (crypt_blowfish:s buggkompatibla läge) och `$2$` stöds inte.

**Lösenord (reglerna ligger i `BCryptPasswordHasher`).**
- `null` ger `NullPointerException` i `hash` och `matches`.
- Lösenordet kodas som UTF-8 med `CodingErrorAction.REPORT` och normaliseras inte, som i
  `Pbkdf2PasswordHasher`. Spring gör `getBytes(UTF_8)`, så varje giltigt lösenord blir samma byte
  i båda.
- Ett lösenord godtas om det är **1–72 byte** UTF-8 och **inte innehåller U+0000**. Ett lösenord
  med fler än 72 `char` avvisas före kodningen, eftersom varje `char` blir minst en byte.
- Ett lösenord som inte godtas ger `IllegalArgumentException` i `hash`, utan lösenordet i
  meddelandet, och `false` i `matches`. I inget av fallen räknas någon bcrypt.
- Byte-arrayerna nollställs efter användning: lösenordet, nyckeln, Blowfish-tillståndet och den
  räknade hashen.
- Motivering: bcrypt läser bara 72 byte, så ett längre lösenord skulle tyst räknas som sina första
  72 byte. Det är CVE-2025-22228 i Spring Security, där två olika långa lösenord godtogs som
  samma. Med NUL i lösenordet kan två olika lösenord ge samma cykliska nyckel: `"a"` och
  `"a\u0000a"` ger båda `a\0a\0a\0…`. C-implementationer slutar dessutom läsa vid NUL. Med 1–72
  byte utan NUL ger två olika lösenord alltid olika nyckelströmmar.
- Konsekvens som ska dokumenteras: en hash som Spring skrev för ett lösenord över 72 byte (före
  Springs rättelse) kan inte verifieras med hela lösenordet. Användaren måste byta lösenord.

**Lagringsformat och tolkning.**
- `hash` skriver `$2a$<kostnad med två siffror>$<22 tecken salt><31 tecken hash>`, alltså exakt 60
  tecken. Kodningen är bcrypts Base64, med alfabetet
  `./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789` och utan utfyllnad.
  Versionen är alltid `$2a$`, som Springs standard. Saltet är 16 byte från en `new SecureRandom()`
  per hasher.
- Tolkningen är strikt och fail-closed. En lagrad hash godtas bara om den
  - har exakt 60 tecken, vilket kontrolleras först;
  - börjar med exakt `$2a$`, `$2b$` eller `$2y$`;
  - har kostnaden som två ASCII-siffror från `04` till `31`, följda av `$`;
  - har 53 tecken ur alfabetet ovan, där saltet (22 tecken) och hashen (31 tecken) är kanoniska.
    Det betyder att de oanvända låga bitarna i sista tecknet är noll: saltets sista tecken är ett
    av `.Oeu` och hashens ett av `.CGKOSWaeimquy26`.

  Allt annat ger `IllegalArgumentException` i `matches` och `needsRehash`, med ett meddelande som
  inte innehåller strängen. `matches` tolkar hashen före lösenordskontrollen, så en felaktig hash
  ger undantag oavsett lösenord, som i `Pbkdf2PasswordHasher`.
- Varför kanonisk Base64 krävs: Springs `BCrypt.checkpw` jämför hela strängen med en ny kodning av
  saltet. En hash med icke-kanoniskt salt eller hash har alltså aldrig kunnat verifieras i Spring,
  så inget går förlorat, och strängen och byten motsvarar varandra ett till ett. Av samma skäl
  måste Spolas egna hashar vara kanoniska, annars godtar Spring dem inte.
- De 23 avkodade byten jämförs med `MessageDigest.isEqual`.

**Kostnad och `needsRehash`.**
- `BCryptPasswordHasher.defaults()` har kostnad 10, som Springs `BCryptPasswordEncoder()`.
  `BCryptPasswordHasher.of(cost)` tar 4–31 som Spring, annars `IllegalArgumentException`. Javadoc
  rekommenderar minst 10. Konstanterna `MIN_COST`, `MAX_COST`, `DEFAULT_COST` och
  `MAX_PASSWORD_BYTES` är publika.
- Tolkningen godtar kostnad 4–31, som behovet kräver. Javadoc och PROGRESS.md säger att en lagrad
  hash med hög kostnad tar lång tid: kostnad 31 är ungefär 2 miljoner gånger kostnad 10, alltså
  dygn. Den som kan skriva i användarlagringen kan därför låsa en tråd med en sådan rad.
- `needsRehash` är `true` bara när den lagrade kostnaden är **lägre** än hasharens, som Springs
  `upgradeEncoding` och behovet. Versionen (`$2a$`, `$2b$` eller `$2y$`) och en högre kostnad ger
  `false`. Det skiljer sig medvetet från `Pbkdf2PasswordHasher`, som byter vid varje skillnad, och
  det dokumenteras.
- `toString()` är `BCryptPasswordHasher[cost=10]`.

**Delegerande hasher (`PasswordHasher.delegating`).**
- Lagringsformatet är `{id}<delegatens hash>`, som i Springs `DelegatingPasswordEncoder`.
- `hash` ger `"{" + defaultId + "}" + delegat.hash(p)`. Delegatens undantag, till exempel för ett
  för långt lösenord, släpps igenom. En delegat som returnerar `null` ger `NullPointerException`.
- `matches(p, encoded)`:
  - `null` ger `NullPointerException`.
  - Strängen måste börja med `{`. Id:t är texten fram till första `}` och måste vara exakt ett av
    de konfigurerade id:na (skiftlägeskänsligt). Sökningen efter `}` går högst 101 tecken in,
    eftersom ett id är högst 100 tecken.
  - Allt annat ger `IllegalArgumentException`. Meddelandet innehåller varken strängen eller texten
    mellan klamrarna, eftersom den kan vara en hash (`{$2a$10$...}`). Det får lista de
    konfigurerade id:na.
  - Resten skickas till delegatens `matches`, och dess `IllegalArgumentException` släpps igenom
    oförändrat.
- En hash **utan** prefix är ett datafel (`IllegalArgumentException`), som med Springs standard
  (`UnmappedIdPasswordEncoder`). Det finns ingen reservhasher för hashar utan prefix
  (`setDefaultPasswordEncoderForMatches`). En ren hash från `BCryptPasswordEncoder` verifieras
  direkt med `BCryptPasswordHasher`.
- `needsRehash(encoded)` tolkar prefixet på samma sätt. Delegatens `needsRehash` anropas alltid på
  resten, så att en felaktig rest ger `IllegalArgumentException` också för ett annat id än
  standard-id:t. Svaret är `true` om id:t inte är standard-id:t eller om delegaten svarar `true`.
- När hasharen skapas:
  - `null` som standard-id, karta, nyckel eller värde ger `NullPointerException`;
  - en tom karta, ett standard-id som inte finns i kartan och ett id som inte är 1–100 tecken
    utskrivbar ASCII (0x21–0x7E) utan `{` och `}` ger `IllegalArgumentException`, med ett
    meddelande som namnger problemet;
  - kartan kopieras, så ändringar efteråt syns inte;
  - resultatet är oföränderligt och trådsäkert om delegaterna är det.
- `toString()` visar standard-id:t och de sorterade id:na, aldrig en hash:
  `DelegatingPasswordHasher[default=bcrypt, ids=[bcrypt, pbkdf2]]`.
- Id:na är appens namn. `"pbkdf2"` i användningsfallet läser Spolas PHC-format
  (`Pbkdf2PasswordHasher`), inte formatet från Springs `Pbkdf2PasswordEncoder`. Det står i Javadoc
  och README.

**`Authenticator` och `InMemoryUserStore` ändras inte.**
- De arbetar genom `PasswordHasher`. Dummyhashen blir `{bcrypt}$2a$10$...` med standardalgoritmen.
- `upgrader(hasher)` flyttar en användare med `{pbkdf2}...` eller med lägre bcrypt-kostnad till
  standardinställningarna.
- En användare med PBKDF2-hash och ett lösenord över 72 byte kan logga in. Uppgraderingen
  misslyckas då med `IllegalArgumentException` från `hash`, som `Authenticator` loggar med
  klassnamnet, och PBKDF2-hashen ligger kvar.

### Testvektorer
Alla vektorer nedan kontrollerades mot libxcrypt:s `crypt(3)` under planeringen. Lösenorden står
som Java-literaler. Kostnad 12 tar ungefär 0,3 s per vektor i Java, så de fem raderna med kostnad
12 får utelämnas om bygget annars blir märkbart långsammare. De övriga ska vara med.

Publicerade vektorer ur jBCrypt:s testsvit (Damien Miller), som Springs `BCrypt` bygger på:
```
""                                             $2a$06$DCq7YPn5Rq63x1Lad4cll.TV4S6ytwfsfvkgY8jIucDrjc8deX1s.
""                                             $2a$08$HqWuK6/Ng6sg9gQzbLrgb.Tl.ZHfXLhvt/SgVyWhQqgqcZ7ZuUtye
""                                             $2a$10$k1wbIrmNyFAPwPVPSVa/zecw2BCEnBwVS2GbrmgzxFUOqW9dk4TCW
""                                             $2a$12$k42ZFHFWqBp3vWli.nIn8uYyIkbvYRvodzbfbK18SSsY.CsIQPlxO
"a"                                            $2a$06$m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe
"a"                                            $2a$08$cfcvVd2aQ8CMvoMpP2EBfeodLEkkFJ9umNEfPD18.hUF62qqlC/V.
"a"                                            $2a$10$k87L/MF28Q673VKh8/cPi.SUl7MU/rWuSiIDDFayrKk/1tBsSQu4u
"a"                                            $2a$12$8NJH3LsPrANStV6XtBakCez0cKHXVxmvxIlcz785vxAIZrihHZpeS
"abc"                                          $2a$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i
"abc"                                          $2a$08$Ro0CUfOqk6cXEKf3dyaM7OhSCvnwM9s4wIX9JeLapehKK5YdLxKcm
"abc"                                          $2a$10$WvvTPHKwdBJ3uk0Z37EMR.hLA2W6N9AEBhEgrAOljy2Ae5MtaSIUi
"abc"                                          $2a$12$EXRkfkdmXn2gzds2SSitu.MW9.gAVqa9eLS1//RYtYCmB1eLHg.9q
"abcdefghijklmnopqrstuvwxyz"                   $2a$06$.rCVZVOThsIa97pEDOxvGuRRgzG64bvtJ0938xuqzv18d3ZpQhstC
"abcdefghijklmnopqrstuvwxyz"                   $2a$08$aTsUwsyowQuzRrDqFflhgekJ8d9/7Z3GV3UcgvzQW3J5zMyrTvlz.
"abcdefghijklmnopqrstuvwxyz"                   $2a$10$fVH8e28OQRj9tqiDXs1e1uxpsjN0c7II7YPKXua2NAKYvM6iQk7dq
"abcdefghijklmnopqrstuvwxyz"                   $2a$12$D4G5f18o7aMMfwasBL7GpuQWuP3pkrZrOAnqP.bmezbMng.QwJ/pG
"~!@#$%^&*()" + " ".repeat(6) + "~!@#$%^&*()PNBFRD"   $2a$06$fPIsBO8qRqkjj273rfaOI.HtSV9jLDpTbZn782DC6/t7qT67P6FfO
"~!@#$%^&*()" + " ".repeat(6) + "~!@#$%^&*()PNBFRD"   $2a$08$Eq2r4G/76Wv39MzSX262huzPz612MZiYHVUJe/OcOql2jo4.9UxTW
"~!@#$%^&*()" + " ".repeat(6) + "~!@#$%^&*()PNBFRD"   $2a$10$LgfYWkbzEvQ4JakH7rOvHe0y8pHKF9OaFgwUZ2q7W2FFZmZzJYlfS
"~!@#$%^&*()" + " ".repeat(6) + "~!@#$%^&*()PNBFRD"   $2a$12$WApznUOJfkEGSmYRfnkrPOr466oFDCaj4b6HY3EXGvfxm43seyhgC
```

Publicerade vektorer ur Openwall crypt_blowfish (`wrapper.c`). `L98` är
`"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789chars after 72 are ignored"`
(98 byte), och dess första 72 byte är `L72 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"`:
```
"U*U"        $2a$05$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW
"U*U*"       $2a$05$CCCCCCCCCCCCCCCCCCCCC.VGOzA784oUp/Z0DY336zx7pLYAy0lwK
"U*U*U"      $2a$05$XXXXXXXXXXXXXXXXXXXXXOAcXxm9kjPGEMsLznoKqmqw7tc8WCx4a
L98          $2a$05$abcdefghijklmnopqrstuu5s2v8.iXieOjg/.AySBTTZIIVFJeBui
""           $2a$05$CCCCCCCCCCCCCCCCCCCCC.7uG0VCzI2bS7j6ymqJi9CdcdxiRTWNy
bytes a3         $2y$05$/OK.fbVrR/bpIqNJ5ianF.Sa7shbm4.OzKpvFnX1pQLmQW96oUlCq
bytes ff ff a3   $2b$05$/OK.fbVrR/bpIqNJ5ianF.CE5elHaaO4EbggVDjb8P19RukzXSM3e   (samma hashdel med $2y$, och i Spola med $2a$)
```

Oberoende vektorer från libxcrypt (`$2b$`, kostnad 05, salt `Ro0CUfOqk6cXEKf3dyaM7O`; `$2a$` och
`$2y$` ger samma hashdel):
```
"påsswörd€"                         (13 byte)  $2b$05$Ro0CUfOqk6cXEKf3dyaM7O.S2ZSxjNS5nuoViyt6yOY7ibmQwDI9q
"密码"                      (6 byte)   $2b$05$Ro0CUfOqk6cXEKf3dyaM7ODF6gsKUXq4Djycywf7GhU9udo5XTFB2
"🔑"  (U+1F511)           (4 byte)   $2b$05$Ro0CUfOqk6cXEKf3dyaM7Ohl9xa58bNl6EQFRekkrSqQExcldbnyi
"é"                            (2 byte)   $2b$05$Ro0CUfOqk6cXEKf3dyaM7OrPiNk.sCjPBJgB586DXbvR7BCyIeK4u
"é"                           (3 byte)   $2b$05$Ro0CUfOqk6cXEKf3dyaM7OxTExh/ADjMJUTgkLPpLHB9EL0C7gukq
"0123456789".repeat(7) + "a"        (71 byte)  $2b$05$Ro0CUfOqk6cXEKf3dyaM7O6.RPl6KXlZILu4rgREbR4cKEWJAEJO2
"0123456789".repeat(7) + "ab"       (72 byte)  $2b$05$Ro0CUfOqk6cXEKf3dyaM7ODEomjXJBnwUsayvMT.y0r2XwXzL7pLm
"0123456789".repeat(7) + "abc"      (73 byte)  $2b$05$Ro0CUfOqk6cXEKf3dyaM7ODEomjXJBnwUsayvMT.y0r2XwXzL7pLm   (samma som 72)
"€".repeat(24)                 (72 byte)  $2b$05$Ro0CUfOqk6cXEKf3dyaM7OBEbxJMKi50s1SLoOuLvN/iq/buH/sla
"€".repeat(23) + "ab"          (71 byte)  $2b$05$Ro0CUfOqk6cXEKf3dyaM7OAaksHpP8MpJaZejZlDtYT0IoDUyo31m
```

Användningsfallets vektorer från libxcrypt (salt = byten `0x10`–`0x1f`, kodat
`C/CQCvOTDfaWEPmZF/ycFu`):
```
"hemligt"          $2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e
"hemligt"          $2a$04$C/CQCvOTDfaWEPmZF/ycFuUeA49K0sshzyZIXUTBmHM4.xmgxaWVq
"hunter2hunter2"   $2a$10$C/CQCvOTDfaWEPmZF/ycFuijXL7x1mYyENSqiAN9JavcgfCUlLFPC
```

Publicerade Blowfish-vektorer (Eric Young, ECB; nyckel, klartext, chiffertext i hex), som också
JDK:ns `Cipher` ger:
```
0000000000000000  0000000000000000  4EF997456198DD78
FFFFFFFFFFFFFFFF  FFFFFFFFFFFFFFFF  51866FD5B85ECB8A
3000000000000000  1000000000000001  7D856F9A613063F2
0123456789ABCDEF  1111111111111111  61F9C3802281B096
FEDCBA9876543210  0123456789ABCDEF  0ACEAB0FC6A0A28D
```

Har maskinen `perl` med libxcrypt kan `perl -e 'print crypt(LÖSENORD, INSTÄLLNING)'` kontrollera
fler vektorer under arbetet. Testerna får inte anropa externa program, så vektorerna står i dem som
konstanter.

## Krav
- `BCryptPasswordHasher` och `PasswordHasher.delegating` enligt besluten. Javadoc beskriver
  formatet, tolkningen, lösenordsreglerna (72 byte, U+0000, CVE:n), kostnaderna, `needsRehash`
  och formatet med `{id}`. Javadoc för `PasswordHasher` och för `module-info.java` nämner de nya
  implementationerna.
- En paketprivat bcrypt-primitiv som tar råa byte av vilken längd som helst, också 0 och över 72,
  så att alla vektorerna kan testas. Dessutom en paketprivat väg som formaterar hela strängen från
  ett givet salt. `hash` och vektortesterna går genom samma väg.
- `README.md` i `modules/security/` får ett nytt avsnitt "BCrypt och hashar från Spring":
  - Spring-koden (`PasswordEncoderFactories.createDelegatingPasswordEncoder()` eller
    `new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(), ...))`)
    bredvid Spola-exemplet. Exemplet är behovets kod i en testkälla (till exempel
    `BCryptExample.java`) som kompileras, och ett test kontrollerar att README visar den oförändrad.
  - Nya rader i jämförelsetabellen: `BCryptPasswordEncoder` mot `BCryptPasswordHasher`,
    `DelegatingPasswordEncoder` mot `PasswordHasher.delegating` och `upgradeEncoding` mot
    `needsRehash`.
  - I avvikelsetabellen ersätts raden "Bara PBKDF2-SHA256; ingen BCrypt ...". Nya rader tillkommer
    om: 72 byte och U+0000, kanonisk Base64, `needsRehash` bara vid lägre kostnad, alltid `$2a$`,
    exakta id utan reservhasher, och att `{pbkdf2}` är Spolas format.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och de nya testerna körs i
   `spola.security.SecurityTests`.
2. Modulens gränser och de befintliga testerna:
   - `requires` och `exports` i `modules/security/src/module-info.java` är oförändrade (inga
     `requires`, `exports spola.security`).
   - Huvudkoden importerar inget från `java.lang.reflect` eller `java.lang.invoke` och använder
     inte `javax.crypto.Cipher`.
   - `git diff` visar inga ändringar under andra modulers `src/` eller `test/` och ingen ändring i
     `Build.java`.
   - Av de befintliga testfilerna i `spola.security` ändras bara `SecurityTests` (registreringen),
     `SourceRulesTests` (antalet filer, och att de nya filerna följer samma förbjudna lista, att
     `BCryptPasswordHasher` använder `new SecureRandom()` och `MessageDigest.isEqual`) och
     `ReadmeTests` (avvikelseraderna). Deras övriga förväntningar är oförändrade.
3. Behovets acceptans, genom exemplet i README (kompilerad testkälla med behovets kod):
   - `hasher.matches("hemligt", "{bcrypt}$2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e")`
     är `true`, och med `"hemligT"` är det `false`.
   - `BCryptPasswordHasher.defaults().matches("hemligt", "$2a$10$C/CQ...")` är `true` (Springs
     hash utan prefix, direkt).
   - `hasher.hash("hemligt")` matchar `\{bcrypt\}\$2a\$10\$[./A-Za-z0-9]{53}`. Delen efter
     prefixet matchar Springs mönster `\A\$2(a|y|b)?\$(\d\d)\$[./0-9A-Za-z]{53}`, saltet är
     kanoniskt, och hashen verifieras både av `hasher.matches` och, utan prefix, av
     `BCryptPasswordHasher.defaults().matches`.
   - Den paketprivata vägen med `"hemligt"`, kostnad 10 och saltet `0x10`–`0x1f` ger exakt
     `$2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e`. Det visar att Spolas hashar
     godtas av Spring.
   - Autentiseraren från exemplet, med kontona `ada` (`{bcrypt}` + hashen för `"hemligt"`) och
     `bob` (`{bcrypt}` + hashen för `"hunter2hunter2"`), ger `Authenticated` med rollen `USER`
     för rätt lösenord och `Failed(BAD_CREDENTIALS)` för fel lösenord och för en okänd användare.
4. Ett tabelltest med de publicerade vektorerna visar att:
   - primitiven ger exakt varje sträng för de 20 vektorerna ur jBCrypt (de 15 utan kostnad 12 om
     de fem utelämnas) och för alla Openwall-vektorer, också de med råa byte `a3` och `ff ff a3`
     (byte ≥ 0x80 teckenutvidgas inte), och att `ff ff a3` med `$2a$` ger samma hashdel som med
     `$2b$`;
   - `BCryptPasswordHasher.of(4).matches(lösenord, hash)` är `true` för varje vektor vars lösenord
     är en sträng (inte råa byte) på 1–72 byte, och `false` för ett lösenord som skiljer sig i ett
     tecken;
   - samma vektorer med `$2a$` utbytt mot `$2b$` och `$2y$` verifieras också;
   - `matches("", ...)` är `false` för vektorerna med tomt lösenord;
   - **CVE-2025-22228:** för `L98`-vektorn är `matches(L72, ...)` `true`, men `matches(L98, ...)`
     är `false` och `hash(L98)` ger `IllegalArgumentException`.
5. Ett tabelltest med de oberoende vektorerna från libxcrypt visar att:
   - primitiven ger exakt varje sträng, med `$2a$`, `$2b$` och `$2y$`;
   - `matches` är `true` för varje lösenord på 1–72 byte;
   - för 73-byteslösenordet ger primitiven samma sträng som för 72 byte, men `matches` på
     72-byteshashen är `false` och `hash` ger `IllegalArgumentException`;
   - `"é"` och `"é"` ger olika hashar och verifierar inte varandra.
6. Ett test av Blowfish-kärnan visar att:
   - de härledda tabellerna har de åtta kända värdena ovan, också efter att hashar räknats (de
     ändras aldrig);
   - kärnan med Blowfishs vanliga nyckelschema och kryptering ger samma chiffertext som JDK:ns
     `Cipher.getInstance("Blowfish/ECB/NoPadding")` för minst 200 slumpade par av nyckel (4–56
     byte) och block;
   - de fem Eric Young-vektorerna stämmer.
7. Ett test av `BCryptPasswordHasher` visar att:
   - `defaults().hash` (ett anrop) matchar `\$2a\$10\$[./A-Za-z0-9]{53}` och har ett kanoniskt
     salt, och att två hashar av samma lösenord skiljer sig;
   - `of(3)`, `of(32)`, `of(-1)` och `of(Integer.MAX_VALUE)` ger `IllegalArgumentException`, och
     `of(4)` och `of(31)` går att skapa;
   - antalet varv räknas utan spill: en paketprivat hjälpmetod ger `16` för kostnad 4 och
     `2_147_483_648L` för kostnad 31, och primitiven använder den;
   - `toString()` är `BCryptPasswordHasher[cost=<kostnad>]`.
8. Ett test av lösenordsreglerna visar att `hash` ger `IllegalArgumentException` (utan lösenordet
   i meddelandet) och att `matches` mot en giltig hash ger `false` för:
   - ett tomt lösenord;
   - 73 byte ASCII;
   - `"€".repeat(24) + "a"` (73 byte) och `"€".repeat(25)` (75 byte);
   - 73 `char`;
   - ett ensamt surrogattecken;
   - `"\u0000"` och `"a\u0000a"`, där `matches("a\u0000a", hash("a"))` är `false`.

   Det visar också att `null` ger `NullPointerException` i båda, och att 72 byte (ASCII och
   `"€".repeat(24)`) godtas.
9. Ett tabelldrivet test med minst 30 felaktiga lagrade hashar visar att `matches` och
   `needsRehash` ger `IllegalArgumentException` vars meddelande inte innehåller strängen, och att
   `matches` gör det också för ett lösenord som annars avvisas. Fallen täcker:
   - tom sträng, 59 och 61 tecken, ett avslutande `\n`, och en sträng på 10 000 tecken;
   - `$2$`, `$2x$`, `$2c$`, `$2A$`, `$3a$` och ett saknat inledande `$`;
   - kostnad `00`, `03`, `32` och `99`, kostnad med mellanslag eller `+`, och en siffra utanför
     ASCII;
   - ett annat tecken än `$` efter kostnaden;
   - `+`, `=`, `-`, `_`, mellanslag, U+0000 och `é` bland de 53 tecknen;
   - icke-kanoniskt salt (`$2a$06$DCq7YPn5Rq63x1Lad4cll/TV4S6...`) och icke-kanonisk hash (sista
     tecknet `/` i stället för `.`);
   - `{bcrypt}` före en annars giltig hash, och en PBKDF2-sträng från `Pbkdf2PasswordHasher`.

   `null` ger `NullPointerException`.
10. Ett test av `needsRehash` med `of(10)` visar `true` för kostnad 04, och `false` för kostnad 10
    med `$2a$`, `$2b$` och `$2y$`, för kostnad 12 och för kostnad 31. Fallet med kostnad 31 är
    klart på under en sekund, alltså räknas ingen bcrypt.
11. Ett test av `PasswordHasher.delegating` visar att:
    - skapandet ger `NullPointerException` och `IllegalArgumentException` enligt besluten,
      inklusive ett id som är tomt, innehåller `{`, `}`, mellanslag eller ett tecken utanför ASCII,
      eller har 101 tecken;
    - en ändring i den givna kartan efteråt inte syns;
    - `hash` ger `{<standard-id>}` följt av delegatens hash, och `matches` fungerar för varje id;
    - följande ger `IllegalArgumentException` vars meddelande varken innehåller strängen eller
      texten mellan klamrarna: en ren bcrypt-hash utan prefix, `{` utan `}`, `{}`, `{BCRYPT}`,
      `{ bcrypt}`, ett okänt id, `{` + en giltig bcrypt-hash + `}` och ett id på 101 tecken;
    - `{bcrypt}` utan rest och `{bcrypt}` + en PBKDF2-sträng ger delegatens
      `IllegalArgumentException`;
    - `needsRehash` med bcrypt `of(10)` som standard ger `true` för `{bcrypt}` med kostnad 04 och
      för en giltig `{pbkdf2}`-hash, `false` för `{bcrypt}` med kostnad 10 (`$2a$` och `$2b$`) och
      12, och `IllegalArgumentException` för `{pbkdf2}` med en felaktig rest;
    - `toString()` visar standard-id:t och de sorterade id:na men ingen hash.
12. Ett test av `Authenticator` med en delegerande hasher (bcrypt `of(5)` som standard, PBKDF2
    `Pbkdf2PasswordHasher.of(10_000, 16, 32)` som `pbkdf2`, och räknande omslag kring båda) och
    `users.upgrader(hasher)` visar att:
    - en användare med `{pbkdf2}`-hash och rätt lösenord loggar in och får exakt ett
      `upgrade`-anrop med en `{bcrypt}$2a$05$`-hash som verifierar samma lösenord, och att nästa
      inloggning inte ger något anrop;
    - en användare med `{bcrypt}$2a$04$` uppgraderas till kostnad 05, medan `{bcrypt}$2a$05$` och
      `{bcrypt}$2b$05$` inte uppgraderas;
    - fel lösenord ger `BAD_CREDENTIALS` och inget anrop;
    - en okänd användare ger `BAD_CREDENTIALS` och exakt ett `matches`-anrop på bcrypt-delegaten;
    - en `{pbkdf2}`-användare med ett lösenord på 80 tecken loggar in och uppgraderingen
      misslyckas. Loggraden på `WARNING` (fångad via autentiserarens paketprivata `of` med logger)
      nämner `java.lang.IllegalArgumentException` men inte lösenordet, och hashen ligger kvar.
13. Ett test med 200 virtuella trådar som samtidigt autentiserar mot samma `Authenticator` med en
    delegerande hasher (bcrypt `of(4)` och PBKDF2 `of(10_000, 16, 32)`, blandat användare med båda
    algoritmerna och rätt och fel lösenord) visar att varje tråd får exakt sitt förväntade
    resultat. 200 samtidiga `hash`-anrop på `BCryptPasswordHasher.of(4)` ger 200 olika salt, och
    varje hash verifieras.
14. Ett test visar med markörsträngar att inget lösenord och ingen lagrad hash finns i `toString()`
    för `BCryptPasswordHasher` och den delegerande hasharen, eller i meddelandet för något undantag
    som testerna i kriterium 7–12 framkallar. Det gäller också texten mellan klamrarna i
    kriterium 11.
15. `README.md` i `modules/security/` har avsnittet "BCrypt och hashar från Spring" enligt kraven,
    med Spola-exemplet som kompilerad testkälla som ett test jämför med README. Jämförelse- och
    avvikelseraderna kontrolleras av `ReadmeTests`.
16. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten. Ingen tabell med Blowfish-konstanter finns i källkoden; de härleds från π.
17. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.
    - PROGRESS.md beskriver det levererade och säger att behovet `2026-10-13-losenord-bcrypt` är
      levererat och att koden är säkerhetskritisk och bör granskas av en människa. Det listar
      kända begränsningar:
      - lösenord över 72 byte och med U+0000 avvisas, så användare med sådana lösenord från Spring
        måste byta lösenord;
      - en lagrad kostnad på 31 kan låsa en tråd i dygn;
      - medan användare flyttas mellan algoritmer skiljer sig tiden för kända användare med en
        annan algoritm från tiden för okända, så en angripare kan se vilka användarnamn som finns;
      - ingen reservhasher för hashar utan prefix;
      - Springs `{pbkdf2}`-format stöds inte;
      - lösenordet som `String` kan inte nollställas.

      Meningen "Bara PBKDF2-HMAC-SHA256 (ingen BCrypt, ...)" under kända problem för
      `spola.security` rättas.
    - ARCHITECTURE.md får ett avsnitt "Lösenord från Spring: BCrypt och delegerande hasher
      (`spola.security`)" som beskriver:
      - varför bcrypt implementeras i Spola trots REGLER §4 och hur det är verifierat;
      - varför konstanterna härleds från π;
      - semantiken för `$2a$`, `$2b$` och `$2y$`, och varför `$2x$` inte stöds;
      - reglerna för 72 byte och U+0000;
      - den strikta tolkningen och varför kanonisk Base64 krävs;
      - kostnadsgränserna och `needsRehash`;
      - formatet med `{id}`, exakta id, att det inte finns någon reservhasher och felmeddelandena
        utan id;
      - varför primitiven är paketprivat.

    Raden för `spola.security` i modulkatalogen i `framework/MONSTER.md` (motsvarar även
    `BCryptPasswordEncoder` och `DelegatingPasswordEncoder`; ingångarna `BCryptPasswordHasher` och
    `PasswordHasher.delegating`) och i modultabellen i `README.md` är uppdaterade.

## Utanför scope
- SCrypt och Argon2, Springs `{pbkdf2}`-format (hex från `Pbkdf2PasswordEncoder`), `{noop}`,
  `{sha256}`, `{MD5}` och andra äldre kodare.
- En reservhasher för hashar utan prefix (`setDefaultPasswordEncoderForMatches`), `$2x$`, `$2$`
  och motåtgärden i crypt_blowfish:s `$2a$`.
- Val av version för nya hashar (`$2b$`/`$2y$`) och en egen `SecureRandom`.
- En publik bcrypt-primitiv, och förhashning av långa lösenord (SHA-512 före bcrypt), som inte
  går ihop med Spring.
- Ändringar i `Authenticator`, `InMemoryUserStore`, `spola.security.web` och andra moduler.
  Låsning och hastighetsbegränsning. Lika tid mellan olika algoritmer medan användare flyttas.
- Release och ny version (görs för hand).

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion och inga
namnkonventioner. JDK:ns primitiver används där de finns (`SecureRandom`, `MessageDigest.isEqual`,
`CharsetEncoder`, `BigInteger` för π). Blowfish skrivs med heltalsaritmetik enligt beslutet, och
inga konstanttabeller kopieras in. Testerna anropar inga externa program. Inga nya filtyper i
`framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
