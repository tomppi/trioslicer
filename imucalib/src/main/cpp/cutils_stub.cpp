#include <cstring>
extern "C" int property_get(const char* key, char* value, const char* def) {
  (void)key; (void)def;
  if (value) value[0] = 0;
  return 0;
}
