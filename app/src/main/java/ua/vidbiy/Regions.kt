package ua.vidbiy

/**
 * Місце, за яким стежить будильник: область зі статичного списку або район/громада з дерева siren.
 * [uid] — UID області в alerts.in.ua (null для району чи громади), [sirenId] — regionId у siren.pp.ua,
 * [ubillingName] — ключ у відповіді ubilling, [detail] — до якої області/району належить.
 * null у полі джерела означає, що джерело це місце не підтримує.
 */
data class Region(
    val uid: Int?,
    val name: String,
    val sirenId: String? = uid.toString(),
    val ubillingName: String? = name,
    val detail: String? = null,
)

object Regions {
    const val DEFAULT_UID = 31

    val all = listOf(
        Region(31, "м. Київ"),
        Region(29, "Автономна Республіка Крим", sirenId = "9999", ubillingName = null),
        Region(4, "Вінницька область"),
        Region(8, "Волинська область"),
        Region(9, "Дніпропетровська область"),
        Region(28, "Донецька область"),
        Region(10, "Житомирська область"),
        Region(11, "Закарпатська область"),
        Region(12, "Запорізька область"),
        Region(13, "Івано-Франківська область"),
        Region(14, "Київська область"),
        Region(15, "Кіровоградська область"),
        Region(16, "Луганська область"),
        Region(27, "Львівська область"),
        Region(17, "Миколаївська область"),
        Region(18, "Одеська область"),
        Region(19, "Полтавська область"),
        Region(5, "Рівненська область"),
        Region(30, "м. Севастополь", sirenId = null, ubillingName = "Севастополь"),
        Region(20, "Сумська область"),
        Region(21, "Тернопільська область"),
        Region(22, "Харківська область"),
        Region(23, "Херсонська область"),
        Region(3, "Хмельницька область"),
        Region(24, "Черкаська область"),
        Region(26, "Чернівецька область"),
        Region(25, "Чернігівська область"),
    )

    fun bySirenId(id: String): Region? = all.firstOrNull { it.sirenId == id }

    fun byUid(uid: Int): Region = all.firstOrNull { it.uid == uid } ?: all.first()
}
