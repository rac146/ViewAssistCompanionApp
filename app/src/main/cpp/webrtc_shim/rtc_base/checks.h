#ifndef RTC_BASE_CHECKS_H_
#define RTC_BASE_CHECKS_H_

#include <assert.h>

#ifdef NDEBUG
#define RTC_DCHECK(condition) ((void)0)
#define RTC_DCHECK_EQ(a, b) ((void)0)
#define RTC_DCHECK_NE(a, b) ((void)0)
#define RTC_DCHECK_LT(a, b) ((void)0)
#define RTC_DCHECK_LE(a, b) ((void)0)
#define RTC_DCHECK_GT(a, b) ((void)0)
#define RTC_DCHECK_GE(a, b) ((void)0)
#else
#define RTC_DCHECK(condition) assert((condition))
#define RTC_DCHECK_EQ(a, b) assert(((a) == (b)))
#define RTC_DCHECK_NE(a, b) assert(((a) != (b)))
#define RTC_DCHECK_LT(a, b) assert(((a) < (b)))
#define RTC_DCHECK_LE(a, b) assert(((a) <= (b)))
#define RTC_DCHECK_GT(a, b) assert(((a) > (b)))
#define RTC_DCHECK_GE(a, b) assert(((a) >= (b)))
#endif

#define RTC_CHECK(condition) assert((condition))
#define RTC_CHECK_EQ(a, b) assert(((a) == (b)))
#define RTC_CHECK_NE(a, b) assert(((a) != (b)))
#define RTC_CHECK_LT(a, b) assert(((a) < (b)))
#define RTC_CHECK_LE(a, b) assert(((a) <= (b)))
#define RTC_CHECK_GT(a, b) assert(((a) > (b)))
#define RTC_CHECK_GE(a, b) assert(((a) >= (b)))

#define RTC_DCHECK_NOTREACHED() assert(false)
#define RTC_NOTREACHED() assert(false)

#endif  // RTC_BASE_CHECKS_H_
