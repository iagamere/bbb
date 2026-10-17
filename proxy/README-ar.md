# نشر وسيط PS4 Builder (مرة واحدة، مجانًا)

الهدف: يضع المستخدم IP الـ PS4 فقط، والتوكن يبقى عندك أنت فقط ولا يدخل ملف التطبيق.

1. **أنشئ توكنًا جديدًا** (وألغِ القديم): GitHub > Settings > Developer settings > Fine-grained tokens.
   اختر مستودع `Ps4-AAA` فقط، وصلاحيتي `Contents` و`Actions` = Read and write. لا تُعطِه أي مستودع آخر.
2. ادخل إلى https://dash.cloudflare.com (حساب مجاني) > Workers & Pages > Create > **Create Worker** > Deploy، ثم Edit code، والصق محتوى `worker.js` كله، ثم Deploy.
3. من الـ Worker: Settings > Variables and Secrets:
   - `GITHUB_TOKEN` نوع **Secret** = التوكن الجديد.
   - `REPO` نوع Text = `iagamere/Ps4-AAA`.
4. انسخ عنوان الـ Worker (مثل `https://ps4-builder.xxx.workers.dev`) وضعه في `app/src/main/java/com/ps4/builder/net/Backend.kt` في `PROXY_URL`، ثم ابنِ التطبيق.
5. (موصى به) في Cloudflare: Security > WAF > Rate limiting rules: حدّ عدد الطلبات لكل IP، حتى لا يستهلك أحد دقائق Actions عندك.

حدود يجب أن تعرفها:
- كل المستخدمين يبنون في **مستودع واحد**: ملفاتهم تظهر في تاريخه، وبناءان في الوقت نفسه قد يتعارضان (الثاني يفشل ويعيد المحاولة).
- أي مستخدم يستطيع تشغيل كود داخل Actions على حسابك (هذا جوهر التطبيق). الحدّ الوحيد هو حدّ المعدّل وحجم الطلب (60 MB).
