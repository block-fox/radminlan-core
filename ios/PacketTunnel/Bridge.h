#ifndef RADMINLAN_APPLE_BRIDGE_H
#define RADMINLAN_APPLE_BRIDGE_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct Core Core;
Core *rlc_start(const char *profile);
void rlc_stop(Core *core);
bool rlc_send(Core *core, const uint8_t *bytes, size_t length);
size_t rlc_receive(Core *core, uint8_t *bytes, size_t capacity);
size_t rlc_status(Core *core, uint8_t *bytes, size_t capacity);
uint64_t rlc_manage(Core *core, const uint8_t *request, size_t length);
#endif
