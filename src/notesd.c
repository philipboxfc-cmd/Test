/*
 * notesd - a tiny notes web app for the Kindle Keyboard (Kindle 3, Wi-Fi).
 *
 * A single static binary: a minimal HTTP/1.0 server that stores notes as
 * plain UTF-8 .txt files and renders an e-ink friendly HTML UI that is used
 * from the Kindle's built-in browser (http://127.0.0.1:8080/).
 *
 * No threads, no CGI, no busybox applets required. The daemon never keeps a
 * file or working directory on /mnt/us open between requests, so the USB
 * mass storage mode of the Kindle keeps working while it runs.
 *
 * Usage:
 *   notesd [-b 127.0.0.1] [-p 8080] [-d /mnt/us/notes/data]
 *          [-x /mnt/us/documents] [-l logfile] [-L ru|en]
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define NOTESD_VERSION "1.0.0"
#define MAX_HEADERS (16 * 1024)
#define MAX_BODY (1024 * 1024)
#define ID_MAX 64
#define TITLE_MAX 72
#define PREVIEW_MAX 90

static struct {
    const char *bind;
    int port;
    const char *data;
    const char *export_dir;
    const char *log;
    int ru;
} cfg = {"127.0.0.1", 8080, "/mnt/us/notes/data", "/mnt/us/documents", NULL, 1};

/* ------------------------------------------------------------------ */
/* UI strings                                                          */
/* ------------------------------------------------------------------ */

enum {
    S_NOTES, S_NEW, S_SEARCH, S_FIND, S_QUICK, S_QUICK_BTN, S_EMPTY, S_NOTHING_FOUND,
    S_UNTITLED, S_EDIT, S_DELETE, S_EXPORT, S_BACK, S_SAVE, S_CANCEL, S_NEW_NOTE,
    S_EDIT_NOTE, S_CONFIRM_DEL, S_YES_DELETE, S_NO_KEEP, S_EXPORTED, S_EXPORTED_HINT,
    S_NOT_FOUND, S_STORAGE_ERR, S_ABOUT, S_ALL, S_SAVED_IN, S_RESULTS, S_FIRST_LINE_HINT,
    S_COUNT
};

static const char *STR_RU[S_COUNT] = {
    [S_NOTES] = "Заметки",
    [S_NEW] = "+ Новая",
    [S_SEARCH] = "Поиск",
    [S_FIND] = "Найти",
    [S_QUICK] = "Быстрая заметка:",
    [S_QUICK_BTN] = "Записать",
    [S_EMPTY] = "Заметок пока нет. Нажмите «+ Новая» или напишите строку выше.",
    [S_NOTHING_FOUND] = "Ничего не найдено.",
    [S_UNTITLED] = "(без названия)",
    [S_EDIT] = "Изменить",
    [S_DELETE] = "Удалить",
    [S_EXPORT] = "В библиотеку",
    [S_BACK] = "К списку",
    [S_SAVE] = "Сохранить",
    [S_CANCEL] = "Отмена",
    [S_NEW_NOTE] = "Новая заметка",
    [S_EDIT_NOTE] = "Правка",
    [S_CONFIRM_DEL] = "Удалить эту заметку?",
    [S_YES_DELETE] = "Да, удалить",
    [S_NO_KEEP] = "Нет, оставить",
    [S_EXPORTED] = "Заметка скопирована в документы Kindle",
    [S_EXPORTED_HINT] = "Файл появится в библиотеке после перезагрузки или подключения/отключения USB.",
    [S_NOT_FOUND] = "Страница не найдена",
    [S_STORAGE_ERR] = "Хранилище недоступно. Если Kindle подключён к компьютеру по USB — отключите его и обновите страницу.",
    [S_ABOUT] = "О программе",
    [S_ALL] = "Все заметки",
    [S_SAVED_IN] = "Файл",
    [S_RESULTS] = "Результаты",
    [S_FIRST_LINE_HINT] = "Первая строка — заголовок заметки.",
};

static const char *STR_EN[S_COUNT] = {
    [S_NOTES] = "Notes",
    [S_NEW] = "+ New",
    [S_SEARCH] = "Search",
    [S_FIND] = "Find",
    [S_QUICK] = "Quick note:",
    [S_QUICK_BTN] = "Add",
    [S_EMPTY] = "No notes yet. Press \"+ New\" or type a line above.",
    [S_NOTHING_FOUND] = "Nothing found.",
    [S_UNTITLED] = "(untitled)",
    [S_EDIT] = "Edit",
    [S_DELETE] = "Delete",
    [S_EXPORT] = "To library",
    [S_BACK] = "Back to list",
    [S_SAVE] = "Save",
    [S_CANCEL] = "Cancel",
    [S_NEW_NOTE] = "New note",
    [S_EDIT_NOTE] = "Edit",
    [S_CONFIRM_DEL] = "Delete this note?",
    [S_YES_DELETE] = "Yes, delete",
    [S_NO_KEEP] = "No, keep it",
    [S_EXPORTED] = "Note copied to the Kindle documents folder",
    [S_EXPORTED_HINT] = "It shows up in the library after a restart or a USB connect/disconnect.",
    [S_NOT_FOUND] = "Page not found",
    [S_STORAGE_ERR] = "Storage unavailable. If the Kindle is connected over USB, eject it and reload.",
    [S_ABOUT] = "About",
    [S_ALL] = "All notes",
    [S_SAVED_IN] = "File",
    [S_RESULTS] = "Results",
    [S_FIRST_LINE_HINT] = "The first line is the note title.",
};

static const char *T(int id) { return cfg.ru ? STR_RU[id] : STR_EN[id]; }

/* ------------------------------------------------------------------ */
/* Logging                                                             */
/* ------------------------------------------------------------------ */

static void logmsg(const char *fmt, ...)
{
    char ts[32];
    time_t now = time(NULL);
    struct tm tmv;
    localtime_r(&now, &tmv);
    strftime(ts, sizeof ts, "%Y-%m-%d %H:%M:%S", &tmv);

    FILE *f = stderr;
    if (cfg.log) {
        f = fopen(cfg.log, "a");
        if (!f)
            f = stderr;
    }
    fprintf(f, "%s ", ts);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    if (f != stderr)
        fclose(f);
    else
        fflush(f);
}

/* ------------------------------------------------------------------ */
/* Growable buffer                                                     */
/* ------------------------------------------------------------------ */

typedef struct {
    char *p;
    size_t n, cap;
} buf_t;

static void buf_reserve(buf_t *b, size_t extra)
{
    if (b->n + extra + 1 <= b->cap)
        return;
    size_t cap = b->cap ? b->cap : 4096;
    while (cap < b->n + extra + 1)
        cap *= 2;
    char *np = realloc(b->p, cap);
    if (!np) {
        logmsg("out of memory");
        exit(1);
    }
    b->p = np;
    b->cap = cap;
}

static void buf_add(buf_t *b, const char *s, size_t n)
{
    buf_reserve(b, n);
    memcpy(b->p + b->n, s, n);
    b->n += n;
    b->p[b->n] = 0;
}

static void buf_str(buf_t *b, const char *s) { buf_add(b, s, strlen(s)); }

static void buf_fmt(buf_t *b, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    va_list ap2;
    va_copy(ap2, ap);
    int need = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (need < 0) {
        va_end(ap2);
        return;
    }
    buf_reserve(b, (size_t)need);
    vsnprintf(b->p + b->n, (size_t)need + 1, fmt, ap2);
    va_end(ap2);
    b->n += (size_t)need;
}

/* HTML-escape s (n bytes) into b. */
static void buf_esc(buf_t *b, const char *s, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        switch (s[i]) {
        case '&': buf_str(b, "&amp;"); break;
        case '<': buf_str(b, "&lt;"); break;
        case '>': buf_str(b, "&gt;"); break;
        case '"': buf_str(b, "&quot;"); break;
        case '\'': buf_str(b, "&#39;"); break;
        default: buf_add(b, s + i, 1);
        }
    }
}

static void buf_esc_str(buf_t *b, const char *s) { buf_esc(b, s, strlen(s)); }

/* URL-encode for use inside href/query values. */
static void buf_urlenc(buf_t *b, const char *s)
{
    static const char hex[] = "0123456789ABCDEF";
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
            buf_add(b, (const char *)&c, 1);
        } else {
            char e[4] = {'%', hex[c >> 4], hex[c & 15], 0};
            buf_add(b, e, 3);
        }
    }
}

static void buf_free(buf_t *b)
{
    free(b->p);
    b->p = NULL;
    b->n = b->cap = 0;
}

/* ------------------------------------------------------------------ */
/* Small string helpers                                                */
/* ------------------------------------------------------------------ */

static int hexval(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Decode a URL-encoded chunk (form value: '+' is a space). Returns malloc'd. */
static char *url_decode(const char *s, size_t n, int plus_is_space, size_t *outlen)
{
    char *out = malloc(n + 1);
    if (!out)
        return NULL;
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        if (s[i] == '%' && i + 2 < n) {
            int h = hexval(s[i + 1]), l = hexval(s[i + 2]);
            if (h >= 0 && l >= 0) {
                out[o++] = (char)((h << 4) | l);
                i += 2;
                continue;
            }
        }
        if (s[i] == '+' && plus_is_space)
            out[o++] = ' ';
        else
            out[o++] = s[i];
    }
    out[o] = 0;
    if (outlen)
        *outlen = o;
    return out;
}

/* Look up a form/query field. Returns malloc'd decoded value or NULL. */
static char *field_get(const char *data, size_t len, const char *key, size_t *outlen)
{
    size_t klen = strlen(key);
    size_t i = 0;
    while (i < len) {
        size_t j = i;
        while (j < len && data[j] != '&')
            j++;
        /* pair is data[i..j) */
        size_t eq = i;
        while (eq < j && data[eq] != '=')
            eq++;
        size_t kl;
        char *k = url_decode(data + i, eq - i, 1, &kl);
        if (k) {
            int match = (kl == klen && memcmp(k, key, klen) == 0);
            free(k);
            if (match) {
                size_t vs = eq < j ? eq + 1 : j;
                return url_decode(data + vs, j - vs, 1, outlen);
            }
        }
        i = j + 1;
    }
    return NULL;
}

/* Cut a UTF-8 string at most `max` bytes without splitting a code point. */
static size_t utf8_cut(const char *s, size_t n, size_t max)
{
    if (n <= max)
        return n;
    size_t i = max;
    while (i > 0 && ((unsigned char)s[i] & 0xC0) == 0x80)
        i--;
    return i;
}

/*
 * Case-fold ASCII and basic Cyrillic (А-Я, Ё) in place, for search.
 * The byte length never changes: capital Cyrillic letters are two bytes and
 * so are their lowercase forms.
 */
static void fold_case(unsigned char *s, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        if (s[i] < 0x80) {
            s[i] = (unsigned char)tolower(s[i]);
        } else if (s[i] == 0xD0 && i + 1 < n) {
            unsigned char c = s[i + 1];
            if (c >= 0x90 && c <= 0x9F) {           /* А..П -> а..п */
                s[i + 1] = (unsigned char)(c + 0x20);
            } else if (c >= 0xA0 && c <= 0xAF) {    /* Р..Я -> р..я */
                s[i] = 0xD1;
                s[i + 1] = (unsigned char)(c - 0x20);
            } else if (c == 0x81) {                 /* Ё -> ё */
                s[i] = 0xD1;
                s[i + 1] = 0x91;
            }
            i++;
        } else if (s[i] >= 0xC0) {
            /* skip the continuation bytes of other multibyte characters */
            size_t len = (s[i] >= 0xF0) ? 4 : (s[i] >= 0xE0) ? 3 : 2;
            i += len - 1;
        }
    }
}

static int contains_folded(const char *hay, size_t hn, const char *needle_folded, size_t nn)
{
    if (nn == 0)
        return 1;
    if (hn < nn)
        return 0;
    unsigned char *h = malloc(hn);
    if (!h)
        return 0;
    memcpy(h, hay, hn);
    fold_case(h, hn);
    int found = memmem(h, hn, needle_folded, nn) != NULL;
    free(h);
    return found;
}

/* ------------------------------------------------------------------ */
/* Note storage                                                        */
/* ------------------------------------------------------------------ */

static int valid_id(const char *id)
{
    size_t n = strlen(id);
    if (n == 0 || n > ID_MAX)
        return 0;
    for (size_t i = 0; i < n; i++) {
        char c = id[i];
        if (!(isalnum((unsigned char)c) || c == '-' || c == '_'))
            return 0;
    }
    return 1;
}

static void note_path(const char *id, char *out, size_t sz)
{
    snprintf(out, sz, "%s/%s.txt", cfg.data, id);
}

static char *read_file(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    if (!f)
        return NULL;
    buf_t b = {0};
    char tmp[4096];
    size_t r;
    while ((r = fread(tmp, 1, sizeof tmp, f)) > 0)
        buf_add(&b, tmp, r);
    fclose(f);
    if (!b.p)
        buf_add(&b, "", 0);
    if (len)
        *len = b.n;
    return b.p;
}

static int write_file_atomic(const char *path, const char *data, size_t len)
{
    char tmp[1200];
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    FILE *f = fopen(tmp, "wb");
    if (!f)
        return -1;
    if (len && fwrite(data, 1, len, f) != len) {
        fclose(f);
        unlink(tmp);
        return -1;
    }
    if (fflush(f) != 0 || fsync(fileno(f)) != 0) {
        /* fsync may be unsupported on some FAT drivers; keep going */
    }
    fclose(f);
    if (rename(tmp, path) != 0) {
        /* FAT on old kernels may refuse to rename over an existing file */
        unlink(path);
        if (rename(tmp, path) != 0) {
            unlink(tmp);
            return -1;
        }
    }
    return 0;
}

static int storage_ok(void)
{
    struct stat st;
    return stat(cfg.data, &st) == 0 && S_ISDIR(st.st_mode);
}

/* Title = first non-empty line, trimmed, cut to TITLE_MAX bytes. */
static void note_title(const char *body, size_t len, char *out, size_t outsz)
{
    size_t i = 0;
    while (i < len && (body[i] == '\n' || body[i] == '\r' || body[i] == ' ' || body[i] == '\t'))
        i++;
    size_t j = i;
    while (j < len && body[j] != '\n' && body[j] != '\r')
        j++;
    while (j > i && (body[j - 1] == ' ' || body[j - 1] == '\t'))
        j--;
    size_t n = j - i;
    if (n == 0) {
        snprintf(out, outsz, "%s", T(S_UNTITLED));
        return;
    }
    /* callers pass a buffer of at least TITLE_MAX + 8 bytes: room for "…" */
    size_t limit = outsz > 8 ? outsz - 8 : 0;
    size_t cut = utf8_cut(body + i, n, limit < TITLE_MAX ? limit : TITLE_MAX);
    memcpy(out, body + i, cut);
    out[cut] = 0;
    if (cut < n)
        strcat(out, "…");
}

/* Preview = first line after the title line, squashed, cut to PREVIEW_MAX. */
static void note_preview(const char *body, size_t len, char *out, size_t outsz)
{
    size_t i = 0;
    while (i < len && (body[i] == '\n' || body[i] == '\r' || body[i] == ' ' || body[i] == '\t'))
        i++;
    while (i < len && body[i] != '\n' && body[i] != '\r')
        i++;
    /* collect the rest, replacing newlines/runs of whitespace by one space */
    char tmp[PREVIEW_MAX * 2 + 8];
    size_t o = 0;
    int space = 1;
    for (; i < len && o < sizeof tmp - 1; i++) {
        char c = body[i];
        if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
            if (!space) {
                tmp[o++] = ' ';
                space = 1;
            }
        } else {
            tmp[o++] = c;
            space = 0;
        }
    }
    while (o > 0 && tmp[o - 1] == ' ')
        o--;
    tmp[o] = 0;
    size_t cut = utf8_cut(tmp, o, PREVIEW_MAX < outsz - 4 ? PREVIEW_MAX : outsz - 4);
    memcpy(out, tmp, cut);
    out[cut] = 0;
    if (cut < o)
        strcat(out, "…");
}

typedef struct {
    char id[ID_MAX + 1];
    time_t mtime;
    off_t size;
} entry_t;

static int entry_cmp(const void *a, const void *b)
{
    const entry_t *x = a, *y = b;
    if (x->mtime != y->mtime)
        return x->mtime < y->mtime ? 1 : -1;
    return strcmp(y->id, x->id);
}

/* Returns malloc'd array, count in *n; -1 on storage error. */
static int list_notes(entry_t **out)
{
    DIR *d = opendir(cfg.data);
    if (!d)
        return -1;
    entry_t *arr = NULL;
    int n = 0, cap = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        const char *name = de->d_name;
        size_t ln = strlen(name);
        if (ln < 5 || ln - 4 > ID_MAX || strcmp(name + ln - 4, ".txt") != 0)
            continue;
        char id[ID_MAX + 1];
        memcpy(id, name, ln - 4);
        id[ln - 4] = 0;
        if (!valid_id(id))
            continue;
        char path[1200];
        note_path(id, path, sizeof path);
        struct stat st;
        if (stat(path, &st) != 0 || !S_ISREG(st.st_mode))
            continue;
        if (n == cap) {
            cap = cap ? cap * 2 : 64;
            entry_t *na = realloc(arr, (size_t)cap * sizeof *arr);
            if (!na) {
                free(arr);
                closedir(d);
                return -1;
            }
            arr = na;
        }
        strcpy(arr[n].id, id);
        arr[n].mtime = st.st_mtime;
        arr[n].size = st.st_size;
        n++;
    }
    closedir(d);
    if (n > 1)
        qsort(arr, (size_t)n, sizeof *arr, entry_cmp);
    *out = arr;
    return n;
}

static void new_id(char *out, size_t sz)
{
    time_t now = time(NULL);
    struct tm tmv;
    localtime_r(&now, &tmv);
    char base[32];
    strftime(base, sizeof base, "%Y%m%d-%H%M%S", &tmv);
    snprintf(out, sz, "%s", base);
    char path[1200];
    for (int k = 2; k < 1000; k++) {
        note_path(out, path, sizeof path);
        if (access(path, F_OK) != 0)
            return;
        snprintf(out, sz, "%s-%d", base, k);
    }
}

static void fmt_time(time_t t, char *out, size_t sz)
{
    struct tm tmv;
    localtime_r(&t, &tmv);
    strftime(out, sz, "%d.%m.%Y %H:%M", &tmv);
}

/* Normalize line endings to \n and make sure the note ends with a newline. */
static char *normalize_body(const char *in, size_t n, size_t *outn)
{
    char *out = malloc(n + 2);
    if (!out)
        return NULL;
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        if (in[i] == '\r') {
            if (i + 1 < n && in[i + 1] == '\n')
                continue;
            out[o++] = '\n';
        } else {
            out[o++] = in[i];
        }
    }
    if (o == 0 || out[o - 1] != '\n')
        out[o++] = '\n';
    out[o] = 0;
    *outn = o;
    return out;
}

/* ------------------------------------------------------------------ */
/* HTTP                                                                */
/* ------------------------------------------------------------------ */

typedef struct {
    char method[8];
    char path[1024];
    char query[2048];
    char *body;
    size_t body_len;
} request_t;

static int read_all(int fd, void *p, size_t n)
{
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, (char *)p + got, n - got);
        if (r <= 0)
            return -1;
        got += (size_t)r;
    }
    return 0;
}

static int write_all(int fd, const void *p, size_t n)
{
    size_t done = 0;
    while (done < n) {
        ssize_t w = write(fd, (const char *)p + done, n - done);
        if (w <= 0)
            return -1;
        done += (size_t)w;
    }
    return 0;
}

/* Returns 0 on success, -1 on malformed/too large/closed. */
static int read_request(int fd, request_t *rq)
{
    memset(rq, 0, sizeof *rq);
    char *hdr = malloc(MAX_HEADERS + 1);
    if (!hdr)
        return -1;
    size_t n = 0;
    char *end = NULL;
    while (n < MAX_HEADERS) {
        ssize_t r = read(fd, hdr + n, MAX_HEADERS - n);
        if (r <= 0) {
            free(hdr);
            return -1;
        }
        n += (size_t)r;
        hdr[n] = 0;
        end = strstr(hdr, "\r\n\r\n");
        if (end)
            break;
        end = strstr(hdr, "\n\n");
        if (end)
            break;
    }
    if (!end) {
        free(hdr);
        return -1;
    }
    size_t hdr_len = (size_t)(end - hdr) + ((end[1] == '\n' && end[0] == '\r') ? 4 : 2);

    /* request line */
    char *line_end = strpbrk(hdr, "\r\n");
    if (!line_end) {
        free(hdr);
        return -1;
    }
    *line_end = 0;
    char *sp1 = strchr(hdr, ' ');
    if (!sp1) {
        free(hdr);
        return -1;
    }
    *sp1 = 0;
    snprintf(rq->method, sizeof rq->method, "%s", hdr);
    char *target = sp1 + 1;
    char *sp2 = strchr(target, ' ');
    if (sp2)
        *sp2 = 0;
    char *q = strchr(target, '?');
    if (q) {
        *q = 0;
        snprintf(rq->query, sizeof rq->query, "%s", q + 1);
    }
    /* decode the path (percent-encoding only; '+' stays literal) */
    size_t plen;
    char *dec = url_decode(target, strlen(target), 0, &plen);
    if (!dec) {
        free(hdr);
        return -1;
    }
    snprintf(rq->path, sizeof rq->path, "%s", dec);
    free(dec);

    /* Content-Length (case-insensitive), scan remaining headers */
    size_t content_length = 0;
    char *h = line_end + 1;
    while (h < hdr + hdr_len) {
        char *le = strpbrk(h, "\r\n");
        if (!le)
            break;
        size_t ll = (size_t)(le - h);
        if (ll > 15 && strncasecmp(h, "Content-Length:", 15) == 0) {
            content_length = strtoul(h + 15, NULL, 10);
        }
        h = le + 1;
        while (*h == '\r' || *h == '\n')
            h++;
    }

    if (content_length > MAX_BODY) {
        free(hdr);
        return -1;
    }
    if (content_length > 0) {
        rq->body = malloc(content_length + 1);
        if (!rq->body) {
            free(hdr);
            return -1;
        }
        size_t have = n - hdr_len;
        if (have > content_length)
            have = content_length;
        memcpy(rq->body, hdr + hdr_len, have);
        if (have < content_length && read_all(fd, rq->body + have, content_length - have) != 0) {
            free(rq->body);
            rq->body = NULL;
            free(hdr);
            return -1;
        }
        rq->body[content_length] = 0;
        rq->body_len = content_length;
    }
    free(hdr);
    return 0;
}

static void send_response(int fd, int status, const char *reason, const char *ctype,
                          const char *extra_headers, const char *body, size_t len)
{
    buf_t h = {0};
    buf_fmt(&h,
            "HTTP/1.0 %d %s\r\n"
            "Content-Type: %s\r\n"
            "Content-Length: %zu\r\n"
            "Cache-Control: no-cache\r\n"
            "Connection: close\r\n"
            "%s"
            "\r\n",
            status, reason, ctype, len, extra_headers ? extra_headers : "");
    if (write_all(fd, h.p, h.n) == 0 && len)
        write_all(fd, body, len);
    buf_free(&h);
}

static void send_html(int fd, int status, const char *reason, buf_t *b)
{
    send_response(fd, status, reason, "text/html; charset=utf-8", NULL, b->p ? b->p : "", b->n);
}

static void send_redirect(int fd, const char *location)
{
    char extra[1200];
    snprintf(extra, sizeof extra, "Location: %s\r\n", location);
    const char *body = "<html><body><a href=\"";
    buf_t b = {0};
    buf_str(&b, body);
    buf_esc_str(&b, location);
    buf_str(&b, "\">OK</a></body></html>");
    send_response(fd, 302, "Found", "text/html; charset=utf-8", extra, b.p, b.n);
    buf_free(&b);
}

/* ------------------------------------------------------------------ */
/* Pages                                                               */
/* ------------------------------------------------------------------ */

static const char CSS[] =
    "body{font-family:Georgia,serif;font-size:19px;color:#000;background:#fff;margin:8px}"
    "h1{font-size:26px;margin:0 0 8px 0}"
    "h2{font-size:22px;margin:8px 0}"
    ".bar{margin:6px 0 10px 0}"
    ".btn{display:inline-block;border:2px solid #000;padding:5px 12px;margin:3px 6px 3px 0;"
    "text-decoration:none;color:#000;font-weight:bold;background:#fff;font-size:18px;font-family:inherit}"
    "a.n{display:block;border:2px solid #000;padding:7px 9px;margin:7px 0;text-decoration:none;color:#000}"
    "a.n b{font-size:20px}"
    "a.n small{font-size:15px;color:#000}"
    ".body{border-top:2px solid #000;padding-top:8px;line-height:1.35;word-wrap:break-word}"
    ".meta{font-size:15px;margin-bottom:6px}"
    "textarea{width:96%;font-size:18px;font-family:inherit;border:2px solid #000;padding:4px}"
    "input.t{font-size:18px;font-family:inherit;border:2px solid #000;padding:4px;width:62%}"
    ".hint{font-size:15px}"
    "form{margin:0}";

static void page_begin(buf_t *b, const char *title)
{
    buf_str(b, "<!DOCTYPE html>\n<html><head>"
               "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">"
               "<title>");
    buf_esc_str(b, title);
    buf_str(b, "</title><style>");
    buf_str(b, CSS);
    buf_str(b, "</style></head><body>\n");
}

static void page_end(buf_t *b)
{
    buf_str(b, "\n</body></html>\n");
}

static void page_simple(int fd, int status, const char *reason, const char *title, const char *msg)
{
    buf_t b = {0};
    page_begin(&b, title);
    buf_fmt(&b, "<h1>%s</h1><p>", "");
    buf_esc_str(&b, msg);
    buf_fmt(&b, "</p><div class=\"bar\"><a class=\"btn\" href=\"/\">%s</a></div>", T(S_BACK));
    page_end(&b);
    send_html(fd, status, reason, &b);
    buf_free(&b);
}

static void page_storage_error(int fd)
{
    page_simple(fd, 503, "Service Unavailable", T(S_NOTES), T(S_STORAGE_ERR));
}

static void page_list(int fd, const char *q)
{
    entry_t *entries = NULL;
    int n = list_notes(&entries);
    if (n < 0) {
        page_storage_error(fd);
        return;
    }

    size_t qn = q ? strlen(q) : 0;
    unsigned char *qf = NULL;
    if (qn) {
        qf = malloc(qn);
        if (qf) {
            memcpy(qf, q, qn);
            fold_case(qf, qn);
        }
    }

    buf_t b = {0};
    page_begin(&b, T(S_NOTES));
    buf_fmt(&b, "<h1>%s</h1>", T(S_NOTES));
    buf_fmt(&b, "<div class=\"bar\"><a class=\"btn\" href=\"/new\">%s</a>"
                "<form action=\"/\" method=\"get\" style=\"display:inline\">"
                "<input class=\"t\" type=\"text\" name=\"q\" value=\"",
            T(S_NEW));
    if (q)
        buf_esc_str(&b, q);
    buf_fmt(&b, "\"> <input class=\"btn\" type=\"submit\" value=\"%s\"></form></div>", T(S_FIND));

    if (!qn) {
        buf_fmt(&b, "<form action=\"/quick\" method=\"post\"><div class=\"bar\">%s<br>"
                    "<input class=\"t\" type=\"text\" name=\"text\" value=\"\"> "
                    "<input class=\"btn\" type=\"submit\" value=\"%s\"></div></form>",
                T(S_QUICK), T(S_QUICK_BTN));
    } else {
        buf_fmt(&b, "<h2>%s: ", T(S_RESULTS));
        buf_esc_str(&b, q);
        buf_fmt(&b, "</h2><div class=\"bar\"><a class=\"btn\" href=\"/\">%s</a></div>", T(S_ALL));
    }

    int shown = 0;
    for (int i = 0; i < n; i++) {
        char path[1200];
        note_path(entries[i].id, path, sizeof path);
        size_t len = 0;
        char *body = read_file(path, &len);
        if (!body)
            continue;
        if (qn && qf && !contains_folded(body, len, (const char *)qf, qn)) {
            free(body);
            continue;
        }
        char title[TITLE_MAX + 8], preview[PREVIEW_MAX + 8], when[32];
        note_title(body, len, title, sizeof title);
        note_preview(body, len, preview, sizeof preview);
        fmt_time(entries[i].mtime, when, sizeof when);
        buf_str(&b, "<a class=\"n\" href=\"/n/");
        buf_urlenc(&b, entries[i].id);
        buf_str(&b, "\"><b>");
        buf_esc_str(&b, title);
        buf_str(&b, "</b><br><small>");
        buf_esc_str(&b, when);
        if (preview[0]) {
            buf_str(&b, " &middot; ");
            buf_esc_str(&b, preview);
        }
        buf_str(&b, "</small></a>\n");
        free(body);
        shown++;
    }
    if (shown == 0)
        buf_fmt(&b, "<p>%s</p>", qn ? T(S_NOTHING_FOUND) : T(S_EMPTY));

    buf_fmt(&b, "<div class=\"bar hint\"><a href=\"/about\">%s</a></div>", T(S_ABOUT));
    page_end(&b);
    send_html(fd, 200, "OK", &b);
    buf_free(&b);
    free(qf);
    free(entries);
}

static void page_view(int fd, const char *id)
{
    char path[1200];
    note_path(id, path, sizeof path);
    size_t len = 0;
    char *body = read_file(path, &len);
    if (!body) {
        if (!storage_ok())
            page_storage_error(fd);
        else
            page_simple(fd, 404, "Not Found", T(S_NOT_FOUND), T(S_NOT_FOUND));
        return;
    }
    struct stat st;
    char when[32] = "";
    if (stat(path, &st) == 0)
        fmt_time(st.st_mtime, when, sizeof when);
    char title[TITLE_MAX + 8];
    note_title(body, len, title, sizeof title);

    buf_t b = {0};
    page_begin(&b, title);
    buf_str(&b, "<h1>");
    buf_esc_str(&b, title);
    buf_str(&b, "</h1><div class=\"meta\">");
    buf_esc_str(&b, when);
    buf_str(&b, "</div><div class=\"bar\">");
    buf_str(&b, "<a class=\"btn\" href=\"/e/");
    buf_urlenc(&b, id);
    buf_fmt(&b, "\">%s</a>", T(S_EDIT));
    buf_str(&b, "<a class=\"btn\" href=\"/d/");
    buf_urlenc(&b, id);
    buf_fmt(&b, "\">%s</a>", T(S_DELETE));
    buf_str(&b, "<a class=\"btn\" href=\"/x/");
    buf_urlenc(&b, id);
    buf_fmt(&b, "\">%s</a>", T(S_EXPORT));
    buf_fmt(&b, "<a class=\"btn\" href=\"/\">%s</a></div>", T(S_BACK));
    buf_str(&b, "<div class=\"body\">");
    /* body with line breaks */
    for (size_t i = 0; i < len; i++) {
        if (body[i] == '\n')
            buf_str(&b, "<br>\n");
        else
            buf_esc(&b, body + i, 1);
    }
    buf_str(&b, "</div>");
    buf_fmt(&b, "<div class=\"bar\"><a class=\"btn\" href=\"/\">%s</a></div>", T(S_BACK));
    page_end(&b);
    send_html(fd, 200, "OK", &b);
    buf_free(&b);
    free(body);
}

static void page_edit(int fd, const char *id /* NULL = new */)
{
    char *body = NULL;
    size_t len = 0;
    if (id) {
        char path[1200];
        note_path(id, path, sizeof path);
        body = read_file(path, &len);
        if (!body) {
            if (!storage_ok())
                page_storage_error(fd);
            else
                page_simple(fd, 404, "Not Found", T(S_NOT_FOUND), T(S_NOT_FOUND));
            return;
        }
    }
    buf_t b = {0};
    page_begin(&b, id ? T(S_EDIT_NOTE) : T(S_NEW_NOTE));
    buf_fmt(&b, "<h1>%s</h1>", id ? T(S_EDIT_NOTE) : T(S_NEW_NOTE));
    buf_str(&b, "<form action=\"/save\" method=\"post\" accept-charset=\"utf-8\">");
    buf_str(&b, "<input type=\"hidden\" name=\"id\" value=\"");
    if (id)
        buf_esc_str(&b, id);
    buf_str(&b, "\">");
    buf_str(&b, "<textarea name=\"body\" rows=\"16\" cols=\"52\">");
    if (body)
        buf_esc(&b, body, len);
    buf_str(&b, "</textarea>");
    buf_fmt(&b, "<div class=\"bar\"><input class=\"btn\" type=\"submit\" value=\"%s\"> ", T(S_SAVE));
    if (id) {
        buf_str(&b, "<a class=\"btn\" href=\"/n/");
        buf_urlenc(&b, id);
        buf_fmt(&b, "\">%s</a>", T(S_CANCEL));
    } else {
        buf_fmt(&b, "<a class=\"btn\" href=\"/\">%s</a>", T(S_CANCEL));
    }
    buf_fmt(&b, "</div><div class=\"hint\">%s</div></form>", T(S_FIRST_LINE_HINT));
    page_end(&b);
    send_html(fd, 200, "OK", &b);
    buf_free(&b);
    free(body);
}

static void page_confirm_delete(int fd, const char *id)
{
    char path[1200];
    note_path(id, path, sizeof path);
    size_t len = 0;
    char *body = read_file(path, &len);
    if (!body) {
        if (!storage_ok())
            page_storage_error(fd);
        else
            page_simple(fd, 404, "Not Found", T(S_NOT_FOUND), T(S_NOT_FOUND));
        return;
    }
    char title[TITLE_MAX + 8];
    note_title(body, len, title, sizeof title);
    free(body);

    buf_t b = {0};
    page_begin(&b, T(S_DELETE));
    buf_fmt(&b, "<h1>%s</h1><p><b>", T(S_CONFIRM_DEL));
    buf_esc_str(&b, title);
    buf_str(&b, "</b></p><form action=\"/delete\" method=\"post\">"
                "<input type=\"hidden\" name=\"id\" value=\"");
    buf_esc_str(&b, id);
    buf_fmt(&b, "\"><div class=\"bar\"><input class=\"btn\" type=\"submit\" value=\"%s\"> ", T(S_YES_DELETE));
    buf_str(&b, "<a class=\"btn\" href=\"/n/");
    buf_urlenc(&b, id);
    buf_fmt(&b, "\">%s</a></div></form>", T(S_NO_KEEP));
    page_end(&b);
    send_html(fd, 200, "OK", &b);
    buf_free(&b);
}

/* Transliteration for Cyrillic code points U+0410..U+044F (А..я), then Ё/ё. */
static const char *const TRANSLIT[64] = {
    "A", "B", "V", "G", "D", "E", "Zh", "Z", "I", "Y", "K", "L", "M", "N", "O", "P",
    "R", "S", "T", "U", "F", "Kh", "Ts", "Ch", "Sh", "Sch", "", "Y", "", "E", "Yu", "Ya",
    "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
    "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya",
};

/* Build an ASCII-safe file name for the exported copy (FAT + Kindle library). */
static void export_name(const char *id, const char *title, char *out, size_t sz)
{
    char clean[64];
    size_t o = 0;
    int last_space = 1;
    for (const unsigned char *p = (const unsigned char *)title; *p && o < sizeof clean - 8; p++) {
        unsigned char c = *p;
        const char *rep = NULL;
        if (isalnum(c)) {
            clean[o++] = (char)c;
            last_space = 0;
            continue;
        }
        if (c == 0xD0 && p[1] >= 0x90 && p[1] <= 0xBF) {           /* А..п */
            rep = TRANSLIT[p[1] - 0x90];
            p++;
        } else if (c == 0xD1 && p[1] >= 0x80 && p[1] <= 0x8F) {    /* р..я */
            rep = TRANSLIT[p[1] - 0x80 + 48];
            p++;
        } else if (c == 0xD0 && p[1] == 0x81) {                    /* Ё */
            rep = "Yo";
            p++;
        } else if (c == 0xD1 && p[1] == 0x91) {                    /* ё */
            rep = "yo";
            p++;
        } else if (c >= 0xC0) {
            /* other multibyte character: skip it entirely */
            size_t len = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : 2;
            for (size_t k = 1; k < len && p[1]; k++)
                p++;
            continue;
        }
        if (rep) {
            size_t rl = strlen(rep);
            if (rl) {
                memcpy(clean + o, rep, rl);
                o += rl;
                last_space = 0;
            }
        } else if (!last_space) {
            clean[o++] = ' ';
            last_space = 1;
        }
    }
    while (o > 0 && clean[o - 1] == ' ')
        o--;
    clean[o] = 0;
    if (o)
        snprintf(out, sz, "Note %s (%s).txt", clean, id);
    else
        snprintf(out, sz, "Note %s.txt", id);
}

static void page_export(int fd, const char *id)
{
    char path[1200];
    note_path(id, path, sizeof path);
    size_t len = 0;
    char *body = read_file(path, &len);
    if (!body) {
        if (!storage_ok())
            page_storage_error(fd);
        else
            page_simple(fd, 404, "Not Found", T(S_NOT_FOUND), T(S_NOT_FOUND));
        return;
    }
    char title[TITLE_MAX + 8];
    note_title(body, len, title, sizeof title);
    char name[160], dest[1400];
    export_name(id, title, name, sizeof name);
    snprintf(dest, sizeof dest, "%s/%s", cfg.export_dir, name);
    int rc = write_file_atomic(dest, body, len);
    free(body);
    if (rc != 0) {
        logmsg("export %s -> %s failed: %s", id, dest, strerror(errno));
        page_storage_error(fd);
        return;
    }
    logmsg("exported %s -> %s", id, dest);
    buf_t b = {0};
    page_begin(&b, T(S_EXPORT));
    buf_fmt(&b, "<h1>%s</h1><p>%s: <b>", T(S_EXPORTED), T(S_SAVED_IN));
    buf_esc_str(&b, dest);
    buf_fmt(&b, "</b></p><p class=\"hint\">%s</p><div class=\"bar\">", T(S_EXPORTED_HINT));
    buf_str(&b, "<a class=\"btn\" href=\"/n/");
    buf_urlenc(&b, id);
    buf_fmt(&b, "\">%s</a><a class=\"btn\" href=\"/\">%s</a></div>", T(S_CANCEL), T(S_BACK));
    page_end(&b);
    send_html(fd, 200, "OK", &b);
    buf_free(&b);
}

static void page_about(int fd)
{
    entry_t *entries = NULL;
    int n = list_notes(&entries);
    free(entries);
    buf_t b = {0};
    page_begin(&b, T(S_ABOUT));
    buf_fmt(&b, "<h1>KindleNotes %s</h1><p class=\"hint\">", NOTESD_VERSION);
    buf_fmt(&b, "bind %s:%d<br>data ", cfg.bind, cfg.port);
    buf_esc_str(&b, cfg.data);
    buf_fmt(&b, " (%s)<br>export ", n < 0 ? "unavailable" : "ok");
    buf_esc_str(&b, cfg.export_dir);
    buf_fmt(&b, "<br>notes: %d<br>ui: %s</p>", n < 0 ? 0 : n, cfg.ru ? "ru" : "en");
    buf_fmt(&b, "<div class=\"bar\"><a class=\"btn\" href=\"/\">%s</a></div>", T(S_BACK));
    page_end(&b);
    send_html(fd, 200, "OK", &b);
    buf_free(&b);
}

/* ------------------------------------------------------------------ */
/* Router                                                              */
/* ------------------------------------------------------------------ */

static void handle_save(int fd, request_t *rq)
{
    if (!storage_ok()) {
        page_storage_error(fd);
        return;
    }
    size_t bl = 0;
    char *id = field_get(rq->body, rq->body_len, "id", NULL);
    char *raw = field_get(rq->body, rq->body_len, "body", &bl);
    if (!raw)
        raw = strdup(""), bl = 0;
    char idbuf[ID_MAX + 1];
    if (id && id[0]) {
        if (!valid_id(id)) {
            free(id);
            free(raw);
            page_simple(fd, 400, "Bad Request", T(S_NOT_FOUND), T(S_NOT_FOUND));
            return;
        }
        snprintf(idbuf, sizeof idbuf, "%s", id);
    } else {
        new_id(idbuf, sizeof idbuf);
    }
    free(id);
    size_t nl = 0;
    char *norm = normalize_body(raw, bl, &nl);
    free(raw);
    if (!norm) {
        page_storage_error(fd);
        return;
    }
    /* an empty new note is simply not created */
    int empty = 1;
    for (size_t i = 0; i < nl; i++)
        if (norm[i] != '\n' && norm[i] != ' ' && norm[i] != '\t') {
            empty = 0;
            break;
        }
    if (empty) {
        free(norm);
        send_redirect(fd, "/");
        return;
    }
    char path[1200];
    note_path(idbuf, path, sizeof path);
    int rc = write_file_atomic(path, norm, nl);
    free(norm);
    if (rc != 0) {
        logmsg("save %s failed: %s", idbuf, strerror(errno));
        page_storage_error(fd);
        return;
    }
    logmsg("saved %s (%zu bytes)", idbuf, nl);
    char loc[200];
    snprintf(loc, sizeof loc, "/n/%s", idbuf);
    send_redirect(fd, loc);
}

static void handle_quick(int fd, request_t *rq)
{
    if (!storage_ok()) {
        page_storage_error(fd);
        return;
    }
    size_t tl = 0;
    char *text = field_get(rq->body, rq->body_len, "text", &tl);
    if (!text) {
        send_redirect(fd, "/");
        return;
    }
    size_t nl = 0;
    char *norm = normalize_body(text, tl, &nl);
    free(text);
    int empty = 1;
    for (size_t i = 0; norm && i < nl; i++)
        if (norm[i] != '\n' && norm[i] != ' ' && norm[i] != '\t') {
            empty = 0;
            break;
        }
    if (!norm || empty) {
        free(norm);
        send_redirect(fd, "/");
        return;
    }
    char idbuf[ID_MAX + 1], path[1200];
    new_id(idbuf, sizeof idbuf);
    note_path(idbuf, path, sizeof path);
    int rc = write_file_atomic(path, norm, nl);
    free(norm);
    if (rc != 0) {
        page_storage_error(fd);
        return;
    }
    logmsg("quick note %s", idbuf);
    send_redirect(fd, "/");
}

static void handle_delete(int fd, request_t *rq)
{
    char *id = field_get(rq->body, rq->body_len, "id", NULL);
    if (!id || !valid_id(id)) {
        free(id);
        page_simple(fd, 400, "Bad Request", T(S_NOT_FOUND), T(S_NOT_FOUND));
        return;
    }
    char path[1200];
    note_path(id, path, sizeof path);
    if (unlink(path) != 0 && errno != ENOENT) {
        logmsg("delete %s failed: %s", id, strerror(errno));
        free(id);
        page_storage_error(fd);
        return;
    }
    logmsg("deleted %s", id);
    free(id);
    send_redirect(fd, "/");
}

static void route(int fd, request_t *rq)
{
    const char *p = rq->path;
    int is_get = strcmp(rq->method, "GET") == 0 || strcmp(rq->method, "HEAD") == 0;
    int is_post = strcmp(rq->method, "POST") == 0;

    if (is_get && strcmp(p, "/") == 0) {
        char *q = field_get(rq->query, strlen(rq->query), "q", NULL);
        page_list(fd, (q && q[0]) ? q : NULL);
        free(q);
        return;
    }
    if (is_get && strcmp(p, "/new") == 0) {
        page_edit(fd, NULL);
        return;
    }
    if (is_get && strcmp(p, "/about") == 0) {
        page_about(fd);
        return;
    }
    if (is_post && strcmp(p, "/save") == 0) {
        handle_save(fd, rq);
        return;
    }
    if (is_post && strcmp(p, "/quick") == 0) {
        handle_quick(fd, rq);
        return;
    }
    if (is_post && strcmp(p, "/delete") == 0) {
        handle_delete(fd, rq);
        return;
    }
    /* /n/ID /e/ID /d/ID /x/ID */
    if (is_get && strlen(p) > 3 && p[0] == '/' && p[2] == '/' && strchr("nedx", p[1])) {
        const char *id = p + 3;
        if (!valid_id(id)) {
            page_simple(fd, 400, "Bad Request", T(S_NOT_FOUND), T(S_NOT_FOUND));
            return;
        }
        switch (p[1]) {
        case 'n': page_view(fd, id); return;
        case 'e': page_edit(fd, id); return;
        case 'd': page_confirm_delete(fd, id); return;
        case 'x': page_export(fd, id); return;
        }
    }
    page_simple(fd, 404, "Not Found", T(S_NOT_FOUND), T(S_NOT_FOUND));
}

/* ------------------------------------------------------------------ */
/* main                                                                */
/* ------------------------------------------------------------------ */

static void usage(void)
{
    fprintf(stderr,
            "notesd %s\n"
            "usage: notesd [-b addr] [-p port] [-d datadir] [-x exportdir] [-l logfile] [-L ru|en]\n",
            NOTESD_VERSION);
}

int main(int argc, char **argv)
{
    int opt;
    while ((opt = getopt(argc, argv, "b:p:d:x:l:L:hv")) != -1) {
        switch (opt) {
        case 'b': cfg.bind = optarg; break;
        case 'p': cfg.port = atoi(optarg); break;
        case 'd': cfg.data = optarg; break;
        case 'x': cfg.export_dir = optarg; break;
        case 'l': cfg.log = optarg; break;
        case 'L': cfg.ru = strcmp(optarg, "en") != 0; break;
        case 'v': printf("notesd %s\n", NOTESD_VERSION); return 0;
        default: usage(); return opt == 'h' ? 0 : 2;
        }
    }
    if (cfg.port <= 0 || cfg.port > 65535) {
        usage();
        return 2;
    }

    signal(SIGPIPE, SIG_IGN);

    /* best effort: create the data dir if the storage is mounted */
    mkdir(cfg.data, 0777);

    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) {
        logmsg("socket: %s", strerror(errno));
        return 1;
    }
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port = htons((uint16_t)cfg.port);
    if (inet_pton(AF_INET, cfg.bind, &sa.sin_addr) != 1) {
        logmsg("bad bind address: %s", cfg.bind);
        return 2;
    }
    if (bind(srv, (struct sockaddr *)&sa, sizeof sa) != 0) {
        logmsg("bind %s:%d: %s", cfg.bind, cfg.port, strerror(errno));
        return 1;
    }
    if (listen(srv, 8) != 0) {
        logmsg("listen: %s", strerror(errno));
        return 1;
    }
    logmsg("notesd %s listening on %s:%d, data=%s", NOTESD_VERSION, cfg.bind, cfg.port, cfg.data);

    for (;;) {
        struct sockaddr_in ca;
        socklen_t cl = sizeof ca;
        int fd = accept(srv, (struct sockaddr *)&ca, &cl);
        if (fd < 0) {
            if (errno == EINTR)
                continue;
            logmsg("accept: %s", strerror(errno));
            usleep(100000);
            continue;
        }
        struct timeval tv = {15, 0};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);

        request_t rq;
        if (read_request(fd, &rq) == 0) {
            route(fd, &rq);
            free(rq.body);
        } else {
            const char *msg = "Bad Request";
            send_response(fd, 400, "Bad Request", "text/plain", NULL, msg, strlen(msg));
        }
        close(fd);
    }
}
