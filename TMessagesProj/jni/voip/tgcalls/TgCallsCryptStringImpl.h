#ifndef TGCALLS_CRYPT_STRING_IMPL_H
#define TGCALLS_CRYPT_STRING_IMPL_H

#include <string>
#include <vector>
#include <cstring>

#include "rtc_base/crypt_string.h"

namespace tgcalls {

class TgCallsCryptStringImpl : public rtc::CryptStringImpl {
public:
    TgCallsCryptStringImpl(std::string const &value) :
    _value(value) {
    }

    virtual ~TgCallsCryptStringImpl() override {
    }

    virtual size_t GetLength() const override {
        return _value.size();
    }

    virtual void CopyTo(char* dest, bool nullterminate) const override {
        memcpy(dest, _value.data(), _value.size());
        if (nullterminate) {
            dest[_value.size()] = 0;
        }
    }
    virtual std::string UrlEncode() const override {
        return _value;
    }
    virtual CryptStringImpl* Copy() const override {
        return new TgCallsCryptStringImpl(_value);
    }

    virtual void CopyRawTo(std::vector<unsigned char>* dest) const override {
        dest->resize(_value.size());
        memcpy(dest->data(), _value.data(), _value.size());
    }

private:
    std::string _value;
};

} // namespace tgcalls

#endif // TGCALLS_CRYPT_STRING_IMPL_H
