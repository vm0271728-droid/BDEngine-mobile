package com.bdengine.mobile

import android.content.Context

internal data class AppStrings(
    val settings: String,
    val scale: String,
    val timeInApp: String,
    val language: String,
    val configure: String,
    val systemDefault: String,
    val splashRecent: String,
    val splashHour: String,
    val splashDay: String,
    val splashWeek: String,
    val splashMonth: String,
    val downloadError: String,
    val saveError: String,
    val downloadFailed: String,
    val unsupportedLink: String,
    val saveAs: String,
    val cancel: String,
    val permissionRequired: String,
    val permissionMessage: String,
    val downloading: String,
    val saving: String,
    val supportedProjectFilesOnly: String,
    val agreementTitle: String,
    val agreementIntro: String,
    val agreementLink: String,
    val agreementCheck: String,
    val agreementOk: String,
    val agreementClose: String,
    val agreementFull: String
)

internal object AppLocale {

    const val MODE_SYSTEM = "system"
    const val MODE_RUSSIAN = "ru"
    const val MODE_ENGLISH = "en"

    private const val PREFS_NAME = "bdengine_mobile_settings"
    private const val PREF_LANGUAGE_MODE = "language_mode_v1"

    fun selectedLanguageMode(context: Context): String {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE_MODE, MODE_SYSTEM)
            .let(::normalizeMode)
    }

    fun setLanguageMode(context: Context, mode: String) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LANGUAGE_MODE, normalizeMode(mode))
            .apply()
    }

    fun isRussian(context: Context): Boolean {
        return when (selectedLanguageMode(context)) {
            MODE_RUSSIAN -> true
            MODE_ENGLISH -> false
            else -> context.resources.configuration.locales[0]
                .language
                .equals("ru", ignoreCase = true)
        }
    }

    fun strings(context: Context): AppStrings = if (isRussian(context)) RUSSIAN else ENGLISH

    fun formatUsageDuration(context: Context, milliseconds: Long): String {
        val safeMs = milliseconds.coerceAtLeast(0L)
        val totalMinutes = safeMs / MINUTE_MS
        val russian = isRussian(context)

        if (totalMinutes < 60L) {
            return if (russian) "$totalMinutes мин" else "$totalMinutes min"
        }

        val totalDays = (safeMs / DAY_MS).coerceAtLeast(1L)
        if (totalDays < MONTH_DAYS) {
            return if (russian) {
                "$totalDays ${russianDays(totalDays)}"
            } else {
                "$totalDays ${englishUnit(totalDays, "day", "days")}"
            }
        }

        if (totalDays < YEAR_DAYS) {
            val months = totalDays / MONTH_DAYS
            val days = totalDays % MONTH_DAYS
            return if (russian) {
                "$months мес $days ${russianDays(days)}"
            } else {
                "$months mo $days ${englishUnit(days, "day", "days")}"
            }
        }

        val years = totalDays / YEAR_DAYS
        val daysAfterYears = totalDays % YEAR_DAYS
        val months = daysAfterYears / MONTH_DAYS
        val days = daysAfterYears % MONTH_DAYS

        return if (russian) {
            "$years ${russianYears(years)} $months мес $days ${russianDays(days)}"
        } else {
            "$years ${englishUnit(years, "yr", "yrs")} $months mo $days ${englishUnit(days, "day", "days")}"
        }
    }

    private fun normalizeMode(mode: String?): String {
        return when (mode) {
            MODE_RUSSIAN -> MODE_RUSSIAN
            MODE_ENGLISH -> MODE_ENGLISH
            else -> MODE_SYSTEM
        }
    }

    private fun englishUnit(value: Long, singular: String, plural: String): String {
        return if (value == 1L) singular else plural
    }

    private fun russianDays(value: Long): String {
        val mod100 = value % 100
        val mod10 = value % 10
        return when {
            mod100 in 11..14 -> "дней"
            mod10 == 1L -> "день"
            mod10 in 2..4 -> "дня"
            else -> "дней"
        }
    }

    private fun russianYears(value: Long): String {
        val mod100 = value % 100
        val mod10 = value % 10
        return when {
            mod100 in 11..14 -> "лет"
            mod10 == 1L -> "год"
            mod10 in 2..4 -> "года"
            else -> "лет"
        }
    }

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24L * 60L * MINUTE_MS
    private const val MONTH_DAYS = 30L
    private const val YEAR_DAYS = 365L

    private val RUSSIAN = AppStrings(
        settings = "Настройки",
        scale = "Масштаб",
        timeInApp = "В приложении",
        language = "Язык",
        configure = "Настроить",
        systemDefault = "Системный",
        splashRecent = "Это приложение не официально, просто открывает страницу.",
        splashHour = "Продолжаем работу.",
        splashDay = "Где ты пропадаешь, бегом за работу!!!",
        splashWeek = "Тебя долго не было...",
        splashMonth = "Я думал ты меня бросил... Тебя не было слишком долго...",
        downloadError = "Ошибка загрузки",
        saveError = "Ошибка сохранения",
        downloadFailed = "Не удалось скачать",
        unsupportedLink = "Неподдерживаемый тип ссылки",
        saveAs = "Сохранить как",
        cancel = "Отмена",
        permissionRequired = "Нужно разрешение",
        permissionMessage = "Разреши доступ к файлам и повтори загрузку",
        downloading = "Идет загрузка...",
        saving = "Идет сохранение...",
        supportedProjectFilesOnly = "Поддерживаются только .bdengine и .bdstudio",
        agreementTitle = "Пользовательское соглашение",
        agreementIntro = "BDEngine Mobile — неофициальное приложение и не связано с разработчиками BDEngine / Block Display. Для продолжения необходимо ознакомиться и принять Пользовательское соглашение.",
        agreementLink = "Пользовательское соглашение",
        agreementCheck = "Я ознакомился(ась) и принимаю условия",
        agreementOk = "ОК",
        agreementClose = "Закрыть",
        agreementFull = """
Пользовательское соглашение BDEngine Mobile
Дата вступления в силу: 27 августа 2026 г.

Настоящее Пользовательское соглашение регулирует использование приложения BDEngine Mobile (далее — «Приложение»). Устанавливая, запуская или используя Приложение, пользователь подтверждает, что ознакомился с настоящим Соглашением и принимает его условия.

1. Назначение приложения
BDEngine Mobile является неофициальным мобильным лаунчером/оболочкой, предназначенным для доступа к веб-редактору BDEngine с устройств Android.

Приложение не является официальным продуктом BDEngine или Block Display, не разрабатывается ими и не имеет официального одобрения или поддержки со стороны их владельцев.

Все права на BDEngine, Block Display, их интерфейс, логотипы, материалы и веб-сервисы принадлежат соответствующим правообладателям.

2. Работа стороннего сервиса
Основная функциональность BDEngine предоставляется сторонним веб-сервисом. Разработчик BDEngine Mobile не контролирует доступность серверов BDEngine, работу учётных записей, облачных проектов, авторизации и других функций стороннего сервиса.

Изменения на стороне BDEngine могут привести к временной или постоянной неработоспособности отдельных функций Приложения.

3. Файлы и проекты
Приложение может предоставлять функции открытия, загрузки и сохранения файлов проектов на устройство.

Пользователь самостоятельно несёт ответственность за сохранность своих проектов. Рекомендуется регулярно создавать резервные копии важных файлов.

Разработчик не гарантирует возможность восстановления проекта в случае повреждения, удаления, несовместимости файла или сбоя стороннего сервиса.

4. Данные и авторизация
Для сохранения авторизации и состояния веб-сайта Приложение может локально хранить cookies, данные WebView и другую информацию сессии на устройстве пользователя.

Данные, которые пользователь вводит непосредственно на сайтах BDEngine или Block Display, могут обрабатываться соответствующими сторонними сервисами согласно их собственным правилам и политике конфиденциальности.

5. Ограничение ответственности
Приложение предоставляется «как есть».

Разработчик не гарантирует отсутствие ошибок, постоянную доступность сервиса, полную совместимость со всеми устройствами или бесперебойную работу сторонних функций.

В пределах, допускаемых применимым законодательством, разработчик не несёт ответственности за потерю проектов, файлов или данных, сбои сторонних серверов, блокировку или потерю учётной записи, несовместимость после обновления BDEngine, а также косвенные убытки, возникшие в результате использования Приложения.

6. Допустимое использование
Пользователь обязуется не использовать Приложение для нарушения законодательства, получения несанкционированного доступа к чужим аккаунтам или данным, вмешательства в работу BDEngine или других сервисов, распространения вредоносного программного обеспечения либо иных противоправных действий.

7. Обновления
Приложение может обновляться без предварительного уведомления для исправления ошибок, обеспечения совместимости с BDEngine, повышения безопасности или добавления новых функций. Функциональность Приложения может изменяться между версиями.

8. Прекращение использования
Пользователь может прекратить действие настоящего Соглашения в любое время, удалив Приложение со своего устройства. Разработчик вправе прекратить разработку или распространение BDEngine Mobile.

9. Изменение соглашения
Настоящее Соглашение может обновляться вместе с новыми версиями Приложения. Если условия существенно изменятся, Приложение может повторно запросить согласие пользователя.

10. Согласие
Устанавливая отметку о принятии условий и нажимая «ОК», пользователь подтверждает, что ознакомился с настоящим Пользовательским соглашением и принимает его условия.
        """.trimIndent()
    )

    private val ENGLISH = AppStrings(
        settings = "Settings",
        scale = "Scale",
        timeInApp = "Time in app",
        language = "Language",
        configure = "Configure",
        systemDefault = "System default",
        splashRecent = "This app is unofficial and simply opens the web page.",
        splashHour = "Let's keep working.",
        splashDay = "Where have you been? Back to work!!!",
        splashWeek = "You have been away for a while...",
        splashMonth = "I thought you left me... You have been gone for too long...",
        downloadError = "Download error",
        saveError = "Save error",
        downloadFailed = "Download failed",
        unsupportedLink = "Unsupported link type",
        saveAs = "Save as",
        cancel = "Cancel",
        permissionRequired = "Permission required",
        permissionMessage = "Allow file access and try the download again",
        downloading = "Downloading...",
        saving = "Saving...",
        supportedProjectFilesOnly = "Only .bdengine and .bdstudio files are supported",
        agreementTitle = "User Agreement",
        agreementIntro = "BDEngine Mobile is an unofficial application and is not affiliated with the developers of BDEngine / Block Display. To continue, you must review and accept the User Agreement.",
        agreementLink = "User Agreement",
        agreementCheck = "I have read and accept the terms",
        agreementOk = "OK",
        agreementClose = "Close",
        agreementFull = """
BDEngine Mobile User Agreement
Effective date: August 27, 2026

This User Agreement governs the use of the BDEngine Mobile application (the “Application”). By installing, launching, or using the Application, the user confirms that they have read this Agreement and accept its terms.

1. Purpose of the Application
BDEngine Mobile is an unofficial mobile launcher/wrapper intended to provide access to the BDEngine web editor from Android devices.

The Application is not an official product of BDEngine or Block Display, is not developed by them, and is not officially approved or supported by their owners.

All rights to BDEngine, Block Display, their interfaces, logos, materials, and web services belong to their respective rights holders.

2. Third-party service
The main functionality of BDEngine is provided by a third-party web service. The developer of BDEngine Mobile does not control the availability of BDEngine servers, user accounts, cloud projects, authentication, or other functions of the third-party service.

Changes made by BDEngine may cause individual features of the Application to become temporarily or permanently unavailable.

3. Files and projects
The Application may provide functions for opening, downloading, and saving project files on the device.

The user is responsible for keeping their projects safe. Important files should be backed up regularly.

The developer does not guarantee that a project can be recovered if it is damaged, deleted, incompatible, or affected by a failure of a third-party service.

4. Data and authentication
To preserve authentication and website state, the Application may store cookies, WebView data, and other session information locally on the user's device.

Data entered directly on BDEngine or Block Display websites may be processed by those third-party services under their own terms and privacy policies.

5. Limitation of liability
The Application is provided “as is”.

The developer does not guarantee that the Application will be error-free, continuously available, compatible with every device, or that third-party functions will operate without interruption.

To the extent permitted by applicable law, the developer is not liable for loss of projects, files, or data; failures of third-party servers; suspension or loss of an account; incompatibility following a BDEngine update; or indirect losses resulting from use of the Application.

6. Acceptable use
The user agrees not to use the Application to violate the law, gain unauthorized access to other people's accounts or data, interfere with BDEngine or other services, distribute malicious software, or engage in other unlawful activity.

7. Updates
The Application may be updated without prior notice to fix errors, maintain compatibility with BDEngine, improve security, or add features. Application functionality may change between versions.

8. Ending use
The user may end this Agreement at any time by removing the Application from their device. The developer may stop developing or distributing BDEngine Mobile.

9. Changes to this Agreement
This Agreement may be updated together with new versions of the Application. If the terms change materially, the Application may request the user's consent again.

10. Consent
By checking the acceptance box and pressing “OK”, the user confirms that they have read this User Agreement and accept its terms.
        """.trimIndent()
    )
}
