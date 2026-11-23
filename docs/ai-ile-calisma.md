# AI ile Çalışma Yöntemi ve Öğrenilen Dersler

Bu proje, öğrenme amaçlı olarak yapılandırılmış bir mentorluk akışıyla geliştirilmektedir. Her faz için önceden kesinleştirilmiş bir adım rehberi, doğrulanmış bir referans çözüm ve mutasyonlarla sınanmış kabul testleri hazırlanır; uygulama kodunu ise geliştirici adım adım kendisi yazar ve doğrular.

## Çalışma Döngüsü

1. **Faz rehberi (`learning/phases/`):** İsimler, imzalar, mimari sınırlar ve beklenen davranışlar adım öncesinde sabitlenir.
2. **Referans ve kabul testleri (`learning/mentor/`):** Her adım için bağımsız kabul testleri (`*KabulTest`) ve mutasyon kontrolleri hazırlanır; geliştiricinin yazdığı kod `kabul.sh` ile sınanır.
3. **Kod incelemesi ve kontrol listesi:** Her adım sonunda derleme (`mvn -B verify`), kabul testleri, mimari kurallar (ArchUnit) ve statik kontroller (`grep`) çalıştırılır.

---

## Hata → Test/İnceleme → Ne Öğrendim?

### Faz 1: Testlerin Yakalayamadığı Kalite ve Tasarım Ayrıntıları

- **Hata:** `Bytes` ve `Glob` sınıflarının Javadoc yorumlarına adım talimatlarından kopmuş metin parçaları yapışmıştı; ayrıca `CommandTable` sınıfı `final` olarak işaretlenmemiş, joker import barındırıyordu ve `CommandHandler` üzerinde `@FunctionalInterface` yoktu.
- **Ne yakaladı?** Hiçbir birim testi ya da kabul testi bunu yakalamadı — davranış doğruydu. Sorunu faz sonu kod incelemesi (statik gözlem) yakaladı.
- **Ne öğrendim?** Davranış testleri yalnızca kodun *ne yaptığına* bakar; kapsülleme, sınıfın uzatılabilirliği (`final`) ve dokümantasyon temizliği gibi nitelikleri doğrulamaz. Bu dersten sonra Faz 2'deki her adımın kontrol listesine doğrudan kaynak kodda talimat artığı ve `final` olmayan `public` sınıf arayan iki `grep` kontrolü eklendi.

### Faz 2: 1 Byte'lık Sınır Hatası (`Errors.unknownCommand`)

- **Hata:** Redis 7.0 bilinmeyen komut hata mesajında tüm argümanlar toplam `128` byte'lık ortak bir bütçeyi paylaşır. `Errors.unknownCommand` içinde kalan yer `MAX_SHOWN_BYTES - args.length()` olarak hesaplanırken açılış tırnağı (`'`) eklendikten *sonra* ölçüm yapıldığında, her argüman kalan bütçeden `1` byte eksik kırpılıyordu.
- **Ne yakaladı?** `ErrorTextKabulTest#argumentsShareOneBudgetOf128Bytes` kabul testi, üç 60 byte'lık argüman verildiğinde son argümanın `'ccc' ` yerine `'cc' ` çıkması gerektiğini doğrulayarak 1 byte'lık sapmayı anında yakaladı.
- **Ne öğrendim?** Sınır ve bütçe hesaplamalarında karakter/tırnak ekleme sırası off-by-one hatalarına çok açıktır. Gerçek sistemle (Docker üzerindeki Redis 7.0.15) doğrulanmış sınır örnekleri üzerinden kabul testi yazmak, ilk bakışta doğru görünen döngü içi hesaplama hatalarını ortaya çıkarır.
