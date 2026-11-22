# 0001. Dil ve platform seçimi

Durum: Kabul edildi

Bağlam:
Mini Redis bir öğrenme projesi. Amaç, Redis benzeri bir key-value sunucusunu sıfırdan yazarak eşzamanlılık, ağ programlama ve bellek yönetimini derinlemesine öğrenmek. Geliştiricinin günlük işi Spring ve kurumsal Java üzerine. Bu yüzden dil, JVM'in iç işleyişini öğrenmeye ve kullanmaya uygun olmalı. Platform kararları ayrıca proje boyunca tekrar tekrar etkiler: derleme aracı, test kütüphaneleri, sürüm politikası ve CI.

Değerlendirilen seçenekler:
- **Java 21 (LTS):** Geliştiricinin bildiği dil. JVM'in GC, JIT ve Java Memory Model gibi konuları projenin konusu olabilir. Java 21'de virtual thread'ler kalıcı olarak gelmiştir. Faz 5'teki thread modeli karşılaştırması için bu gerekli. LTS olduğu için uzun süre güvenlik güncellemesi alır.
- **C:** Redis'in kendi dili. Bellek ve veri yapılarını elle yönetmek mümkün, `fork()` ile copy-on-write snapshot gerçek anlamda kullanılabilir. Bedeli: bellek güvenliği hatalarının (use-after-free, buffer overflow) sorumluluğu tamamen geliştiriciye geçer. Eşzamanlılık hatalarını ayıklamak da daha zor.
- **Go:** Goroutine ve kanallarla eşzamanlılık yazmak kolay. Ancak Java'da bildiğimiz thread, kilit ve `java.util.concurrent` soyutlamalarının kendisi öğrenme hedefi, Go bunları gizler.
- **Rust:** Bellek güvenliği derleme zamanında denetlenir. Bedeli: sahiplik ve ödünç alma kuralları, öğrenme eğrisini projenin asıl konusundan (eşzamanlılık ve sunucu tasarımı) uzaklaştırır.
- **Kotlin:** JVM üzerinde, Java ile aynı çalışma zamanı. Coroutine'ler farklı bir eşzamanlılık modeli getirir. Java'nın thread ve kilit modelini öğrenme hedefiyle uyumsuz.

Karar:
- **Dil: Java 21 LTS.** Derleme aracı Maven. Framework kullanılmaz (Spring, Netty yok): sunucunun asıl işi (veri yapıları, protokol, ağ, thread havuzu) elle yazılır.
- Geliştirme Windows üzerinde, IntelliJ ile yapılır. Gerçek Redis ve `redis-cli` Docker içinde çalışır.
- CI yalnızca Ubuntu'da çalışır. İşletim sistemine özgü kod gelirse (Faz 8'deki dizin fsync'i) CI'a Windows işi eklenir.
- Varsayılan port 6380 (K7): gerçek Redis'in 6379 portuyla çakışmasın.
- Dış kütüphane yalnızca test, log ve ölçüm için kullanılır (JUnit, AssertJ, ArchUnit, SLF4J, JMH).

Sonuçlar:
- **Olumlu:**
  - Geliştiricinin mevcut bilgisi kullanılır, öğrenme eğrisi konuya odaklanır.
  - Virtual thread'ler ve NIO ile iki eşzamanlılık modelini aynı dilde karşılaştırmak mümkün.
  - Bellek güvenliği JVM tarafından sağlanır, veri yapılarındaki hatalar bellek bozulmasına dönüşmez.
  - Maven ve JUnit ekosistemi standart, CI ve IDE desteği hazır.
- **Olumsuz:**
  - **GC duraklamaları p99 gecikmeyi etkileyebilir.** Redis tek iş parçacıklı bir sunucudur. Bir GC duraklaması, o sırada bekleyen tüm istekleri geciktirir. Bu etki Faz 6–9'da ölçülecek, GC karşılaştırmasına kadar kesin bir sayı verilmeyecek.
  - **`fork()` yok (K5).** Redis'in copy-on-write snapshot'ı (BGSAVE) Java'da doğrudan yapılamaz. Snapshot'ın bedeli farklı olacak ve Faz 8'de ayrıca tasarlanacak.
  - **JIT ısınma süresi.** Sunucu başladıktan sonraki ilk isteklerin gecikmesi, ısınmış bir JVM'den yüksek olabilir.
  - Her nesnenin bir başlık (header) maliyeti var. Kısa ömürlü `Bytes` gibi nesneler çok sayıda olduğunda bellek kullanımını artırır.
  - Çalışma için bir JDK 21 kurulumu gerekir. Bu, Redis'in tek bir C derleyicisiyle çalışmasına göre daha fazla adım.
