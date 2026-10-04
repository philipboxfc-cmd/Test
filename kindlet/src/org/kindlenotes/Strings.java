package org.kindlenotes;

/** UI strings (Russian). Kept in one place so an English build is a one-file change. */
public final class Strings {
    public static final String APP = "KindleNotes";
    public static final String NOTES = "Заметки";                                                   // Заметки
    public static final String NEW_NOTE = "+ Новая заметка";             // + Новая заметка
    public static final String NEW_NOTE_MENU = "Новая заметка";         // Новая заметка
    public static final String SEARCH = "Поиск";                                                            // Поиск
    public static final String FIND = "Найти";                                                              // Найти
    public static final String NOTE_FROM = "Заметка от";                                // Заметка от
    public static final String HELP = "Список: джойстик выбирает заметку, "
            + "клавиши страниц листают, "
            + "буквы Q..P открывают строки 1..10.\n"
            + "Редактор: первая строка - заголовок, "
            + "Back - сохранить и выйти, Menu - действия.\n"
            + "Текст сохраняется и при уходе в сон.";
            // Список: джойстик выбирает заметку, клавиши страниц листают, буквы Q..P открывают строки 1..10.
            // Редактор: первая строка - заголовок, Back - сохранить и выйти, Menu - действия.
            // Текст сохраняется и при уходе в сон.
    public static final String CLEAR_SEARCH = "Все заметки";                       // Все заметки
    public static final String EMPTY = "Заметок пока нет. "
            + "Нажмите «+ Новая заметка» "
            + "или клавишу Menu.";                                                  // Заметок пока нет. Нажмите «+ Новая заметка» или клавишу Menu.
    public static final String NOTHING_FOUND = "Ничего не найдено."; // Ничего не найдено.
    public static final String UNTITLED = "(без названия)";                   // (без названия)
    public static final String PAGE = "стр.";                                                                         // стр.
    public static final String PAGE_HINT = "листать - клавиши страниц, "
            + "выбор - джойстик";                                   // листать - клавиши страниц, выбор - джойстик
    public static final String SAVE = "Сохранить";                                      // Сохранить
    public static final String SAVE_BACK = "Сохранить и назад"; // Сохранить и назад
    public static final String DELETE = "Удалить";                                                // Удалить
    public static final String EXPORT = "В библиотеку";                       // В библиотеку
    public static final String CANCEL = "Отмена";                                                      // Отмена
    public static final String DISCARD = "Назад без сохранения"; // Назад без сохранения
    public static final String EDITING = "Правка";                                                     // Правка
    public static final String NEW_NOTE_TITLE = "Новая заметка";        // Новая заметка
    public static final String FIRST_LINE_HINT = "Первая строка - заголовок. "
            + "Back - сохранить и выйти, Menu - действия."; // Первая строка - заголовок. Back - сохранить и выйти, Menu - действия.
    public static final String CONFIRM_DELETE = "Удалить заметку?"; // Удалить заметку?
    public static final String YES_DELETE = "Да, удалить";                              // Да, удалить
    public static final String NO_KEEP = "Нет, оставить";                     // Нет, оставить
    public static final String EXPORTED = "Заметка скопирована в документы Kindle:\n"; // Заметка скопирована в документы Kindle:
    public static final String EXPORTED_HINT = "\n\nПоявится в библиотеке после перезагрузки или подключения USB."; // Появится в библиотеке после перезагрузки или подключения USB.
    public static final String EXPORT_FAILED = "Не удалось записать файл в /mnt/us/documents.\n"
            + "Песочница киндлета не даёт доступа. "
            + "Заметка сохранена в папке программы."; // Не удалось записать файл в /mnt/us/documents. Песочница киндлета не даёт доступа. Заметка сохранена в папке программы.
    public static final String SAVE_FAILED = "Не удалось сохранить заметку. "
            + "См. kindlenotes.log в папке программы."; // Не удалось сохранить заметку. См. kindlenotes.log в папке программы.
    public static final String STORAGE_FAILED = "Хранилище недоступно: "
            + "не удалось создать папку для заметок."; // Хранилище недоступно: не удалось создать папку для заметок.
    public static final String ABOUT = "О программе";                              // О программе
    public static final String OK = "OK";
    public static final String FOLDER = "Папка заметок: ";               // Папка заметок:
    public static final String SHARED_YES = "\n(общая с браузерной версией)"; // (общая с браузерной версией)
    public static final String SHARED_NO = "\n(папка программы, песочница не пустила в /mnt/us/notes)"; // (папка программы, песочница не пустила в /mnt/us/notes)
    public static final String COUNT = "заметок: ";                                               // заметок:

    private Strings() {
    }
}
