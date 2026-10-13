# MD-INSTAL – aplikacja na Androida

Aplikacja ma w środku generator schematów, protokół uruchomienia, radio i link do Iglutech.
Działa bez internetu (poza radiem i Iglutech), bez paska adresu.

## Jak zrobić APK (GitHub, ok. 10 minut, bez Android Studio)
1. Załóż darmowe konto na github.com i utwórz **prywatne** repozytorium (New repository → Private).
2. Rozpakuj ten zip. W repozytorium: Add file → Upload files → przeciągnij **całą zawartość** folderu
   (łącznie z folderem `.github`, plikiem `release.jks` i folderem `app`) → Commit changes.
   Jeśli folder `.github` się nie wgrał: Add file → Create new file, w nazwie wpisz
   `.github/workflows/build.yml` i wklej zawartość pliku build.yml z tego zipa.
3. Wejdź w zakładkę **Actions**. Build ruszy sam (kilka minut). Zielony ptaszek = gotowe.
4. Kliknij ostatnie uruchomienie → na dole **Artifacts → MD-INSTAL-apk** → pobierz zip z plikiem .apk.
5. Prześlij APK na telefon i zainstaluj (Android zapyta o zgodę na instalację z nieznanych źródeł).

Jeśli build się wywali (czerwony krzyżyk), wejdź w nieudany build, skopiuj końcówkę logu i wyślij do Claude.

## Ważne
- `release.jks` to klucz podpisu aplikacji. Trzymaj repozytorium prywatne i zrób kopię tego pliku:
  tylko z tym samym kluczem nowe wersje zainstalują się na starej bez odinstalowania.
- Hasło do klucza: `mdinstal2026` (jest w app/build.gradle.kts).
- Aktualizacja treści: podmień `app/src/main/assets/index.html` w repozytorium, a build zrobi nowy APK.
  Przed wydaniem nowej wersji zwiększ `versionCode` w app/build.gradle.kts.
