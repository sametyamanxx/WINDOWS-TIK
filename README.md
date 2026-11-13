# Windows Tık

Android telefonunu Windows bilgisayarı için Bluetooth fare ve klavye olarak kullan. Dokunmatik yüzey tıklama, sürükleme, iki parmakla kaydırma ve Chrome’da iki parmakla sayfa yakınlaştırma sağlar. Ağdan açma aynı yerel ağdaki Wake-on-LAN destekli bilgisayarlarda kullanılabilir.

## Kurulum

1. [Son GitHub sürümünün](https://github.com/sametyamanxx/WINDOWS-TIK/releases/latest) APK dosyasını Android 9 veya üstü telefona indir ve kur.
2. Telefonda ve bilgisayarda Bluetooth’u aç. Uygulamaya **Yakındaki cihazlar** izni ver.
3. Listeden bilgisayarını seç. Gerekirse Windows’ta **Ayarlar → Bluetooth ve cihazlar → Cihaz ekle → Bluetooth** üzerinden telefonu seç ve eşleştirme kodunu onayla.
4. Touchpad ile imleci hareket ettir. Tek dokunuş sol tık, sabit basılı tutup bırakma sağ tık, basılı tutarak hareket ettirme sürüklemedir.
5. Yazmak için klavye simgesine dokun. Telefonun standart klavyesi doğrudan açılır. Türkçe karakterler için Windows’ta ve uygulamada Türkçe Q kullan.

## Wake-on-LAN

1. BIOS/UEFI’ye gir. Üreticinin güç yönetimi menüsünde **Wake on LAN** veya **Power On By PCI-E** ayarını etkinleştir. ErP ve kapalı durumdan uyandırma desteği için bilgisayarın kılavuzuna bak.
2. Windows’ta **Aygıt Yöneticisi → Ağ bağdaştırıcıları → Ethernet kartı → Özellikler** yolunu aç. Varsa **Wake on Magic Packet** seçeneğini etkinleştir ve **Güç Yönetimi** sekmesinde ağ kartının bilgisayarı uyandırmasına izin ver.
3. CMD’de `ipconfig /all` çalıştır. Aktif Ethernet bağdaştırıcısındaki **Physical Address** ve **IPv4 Address** değerlerini Windows logosuyla açılan paneldeki alanlara gir ve **Kaydet**’e dokun.
4. Telefon aynı yerel ağdayken güç simgesine dokun. Önce uyku modundan dene. Tam kapalı durumdan uyandırma donanıma bağlıdır.

## Uygulama içi güncelleme

Açılışta GitHub’daki son yayın otomatik kontrol edilir. Yeni sürüm bulunduğunda **Güncelle** seçeneği çıkar. APK uygulama içinde indirilir ve doğrulanır. Gerekirse ilk sefer Android’de bu kaynaktan kuruluma izin ver. Kurulum Android’in onay ekranından tamamlanır.

## Kaynaktan derleme ve yayın

1. JDK 17, Gradle 8.9 ve Android SDK Platform 35 ile `gradle assembleDebug` çalıştır. Çıktı `build/outputs/apk/debug/` klasöründedir.
2. Yeni sürümde `versionName` ve `versionCode` değerlerini artır. Manifest ve Gradle yapılandırmasını aynı değerlerde tut. Kurulu sürüm etiketi Android paketinin sürüm adından gelir.
3. Dağıtım APK’sını önceki yayınla aynı özel anahtarla imzala. Anahtarı ve parolasını depoya koyma.
4. GitHub Releases’ta örneğin `v1.3` etiketini oluştur. `Windows-Tik-v1.3.apk` adlı imzalı dosyayı ekle ve yayını son sürüm olarak işaretle. Uygulama, GitHub’ın APK için sağladığı SHA-256 değerini kullanır.

Windows ve Windows simgeleri ilgili marka sahiplerine aittir.
