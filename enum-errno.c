#include <stdio.h>
#include <errno.h>
#include <string.h>

int main(void) {
    for (int i = 1; i <= 128; i++) {
        const char *s = strerror(i);
        if (s) {
            printf("%d %s\n", i, s);
        }
    }
    return 0;
}
