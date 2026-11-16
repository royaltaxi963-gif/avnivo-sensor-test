"""Independent translation test corpus (Uzbek + English source sentences).

Covers the 20 required topic areas. Sentences are intentionally short so the
whole audit stays inside the $30 budget while still exercising numbers, dates,
negation, script and punctuation.
"""
from __future__ import annotations

from typing import Final

# (id, category, uz, en)
ITEMS: Final[tuple[tuple[str, str, str, str], ...]] = (
    ("c01", "greeting", "Salom, yaxshimisiz?", "Hello, how are you?"),
    ("c02", "daily_conversation", "Bugun havo juda issiq.", "The weather is very hot today."),
    ("c03", "question_answer", "Siz qayerda yashaysiz?", "Where do you live?"),
    ("c04", "workplace", "Yig'ilish soat o'nda boshlanadi.", "The meeting starts at ten o'clock."),
    ("c05", "travel_transport", "Poyezd ertalab sakkizda jo'naydi.", "The train departs at eight in the morning."),
    ("c06", "shopping", "Bu ko'ylak juda qimmat.", "This shirt is very expensive."),
    ("c07", "numbers_money", "Menda yuz ellik ming so'm bor.", "I have one hundred and fifty thousand soum."),
    ("c08", "date_time", "Uchrashuv 2026 yil 5 mart kuni.", "The appointment is on March 5, 2026."),
    ("c09", "negation", "Men bu joyni bilmayman.", "I do not know this place."),
    ("c10", "commands_requests", "Iltimos, eshikni yoping.", "Please close the door."),
    ("c11", "complex_grammar", "Agar vaqtim bo'lsa, sizga qo'ng'iroq qilaman.", "If I have time, I will call you."),
    ("c12", "ambiguous_words", "Bu juda qiziq kitob.", "This is a very interesting book."),
    ("c13", "context_idioms", "Ko'nglingizga og'ir olmang.", "Do not take it to heart."),
    ("c14", "long_text", "Ertaga biz yangi loyihani boshlaymiz va kechqurun natijalarni muhokama qilamiz.", "Tomorrow we will start a new project and in the evening we will discuss the results."),
    ("c15", "names_places", "Alisher Navoiy Buxoroda yashagan.", "Alisher Navoi lived in Bukhara."),
    ("c16", "formal_informal", "Hurmatli janob direktor, xat uchun rahmat.", "Dear Mr. Director, thank you for the letter."),
    ("c17", "spelling_punctuation", "Nima uchun kelmadingiz?", "Why did you not come?"),
    ("c18", "writing_systems", "Men o'zbek tilida gaplashaman.", "I speak the Uzbek language."),
    ("c19", "special_unicode", "Bu narx 25% ga oshdi.", "This price increased by 25%."),
    ("c20", "stability_repeat", "Rahmat, sizga yaxshi kun tilayman.", "Thank you, I wish you a good day."),
)

# Numeric tokens that must survive translation (digits, dates, percentages).
NUMERIC_EXPECTATIONS: Final[dict[str, tuple[str, ...]]] = {
    "c07": ("150", "150000"),
    "c08": ("2026", "5", "03"),
    "c19": ("25",),
}

# Latin/Cyrillic/Arabic/Han/other scripts expected per Translation LLM code.
SCRIPT_EXPECTATIONS: Final[dict[str, str]] = {
    "ru": "Cyrillic", "uk": "Cyrillic", "bg": "Cyrillic", "sr": "Cyrillic",
    "mk": "Cyrillic", "be": "Cyrillic", "mn": "Cyrillic", "ky": "Cyrillic",
    "kk": "Cyrillic", "tg": "Cyrillic", "ar": "Arabic", "fa": "Arabic",
    "ur": "Arabic", "iw": "Hebrew", "he": "Hebrew", "hi": "Devanagari",
    "mr": "Devanagari", "ne": "Devanagari", "bn": "Bengali", "gu": "Gujarati",
    "pa": "Gurmukhi", "ta": "Tamil", "te": "Telugu", "kn": "Kannada",
    "ml": "Malayalam", "th": "Thai", "ka": "Georgian", "hy": "Armenian",
    "zh": "Han", "zh-CN": "Han", "zh-Hans": "Han", "zh-Hant": "Han",
    "zh-TW": "Han", "zh-HK": "Han", "ja": "Japanese", "ko": "Korean",
    "km": "Khmer", "lo": "Lao", "my": "Myanmar", "si": "Sinhala",
    "am": "Ethiopic", "el": "Greek",
}

# Round-trip directions: (label, source_field, target_field)
DIRECTIONS: Final[tuple[tuple[str, str, str], ...]] = (
    ("uz2X", "uz", "X"),
    ("en2X", "en", "X"),
    ("X2uz", "X", "uz"),
    ("X2en", "X", "en"),
)


def source_text(item: tuple[str, str, str, str], field: str) -> str:
    """Return the uz or en text of a corpus item."""
    return item[2] if field == "uz" else item[3]
