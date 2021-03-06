/*
 *   The MIT License (MIT)
 *  
 *   Copyright (c) 2015 Shopify Inc.
 *  
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *  
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *  
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 */
package com.shopify.buy.dataprovider;

import android.text.TextUtils;

import com.shopify.buy.model.Checkout;
import com.shopify.buy.model.CreditCard;
import com.shopify.buy.model.GiftCard;
import com.shopify.buy.model.PaymentSession;
import com.shopify.buy.model.PaymentToken;
import com.shopify.buy.model.ShippingRate;
import com.shopify.buy.model.internal.CheckoutWrapper;
import com.shopify.buy.model.internal.GiftCardWrapper;
import com.shopify.buy.model.internal.MarketingAttribution;
import com.shopify.buy.model.internal.PaymentSessionCheckout;
import com.shopify.buy.model.internal.PaymentSessionCheckoutWrapper;
import com.shopify.buy.model.internal.ShippingRatesWrapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import retrofit2.Response;
import retrofit2.Retrofit;
import rx.Observable;
import rx.Scheduler;
import rx.functions.Func1;

import static java.net.HttpURLConnection.HTTP_OK;

/**
 * Default implementation of {@link CheckoutService}
 */
final class CheckoutServiceDefault implements CheckoutService {

    public static final long POLLING_INTERVAL = TimeUnit.MILLISECONDS.toMillis(500);

    public static final long POLLING_TIMEOUT = TimeUnit.SECONDS.toMillis(90);

    final CheckoutRetrofitService retrofitService;

    final String apiKey;

    final String applicationName;

    final NetworkRetryPolicyProvider networkRetryPolicyProvider;

    final PollingPolicyProvider pollingRetryPolicyProvider;

    final Scheduler callbackScheduler;

    CheckoutServiceDefault(
        final Retrofit retrofit,
        final String apiKey,
        final String applicationName,
        final NetworkRetryPolicyProvider networkRetryPolicyProvider,
        final Scheduler callbackScheduler
    ) {
        this.retrofitService = retrofit.create(CheckoutRetrofitService.class);
        this.apiKey = apiKey;
        this.applicationName = applicationName;
        this.networkRetryPolicyProvider = networkRetryPolicyProvider;
        this.callbackScheduler = callbackScheduler;

        pollingRetryPolicyProvider = new PollingPolicyProvider(POLLING_INTERVAL, POLLING_TIMEOUT);
    }

    @Override
    public CancellableTask createCheckout(final Checkout checkout, final Callback<Checkout> callback) {
        return new CancellableTaskSubscriptionWrapper(createCheckout(checkout).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Checkout> createCheckout(final Checkout checkout) {
        if (checkout == null) {
            throw new NullPointerException("checkout cannot be null");
        }

        final Checkout safeCheckout = checkout.copy();
        safeCheckout.setMarketingAttribution(new MarketingAttribution(applicationName));
        safeCheckout.setSourceName("mobile_app");
        return retrofitService
            .createCheckout(new CheckoutWrapper(safeCheckout))
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>())
            .compose(new UnwrapRetrofitBodyTransformer<CheckoutWrapper, Checkout>())
            .onErrorResumeNext(new BuyClientExceptionHandler<Checkout>())
            .observeOn(callbackScheduler);
    }

    @Override
    public CancellableTask updateCheckout(final Checkout checkout, final Callback<Checkout> callback) {
        return new CancellableTaskSubscriptionWrapper(updateCheckout(checkout).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Checkout> updateCheckout(Checkout checkout) {
        if (checkout == null) {
            throw new NullPointerException("checkout cannot be null");
        }

        final Checkout safeCheckout = new Checkout(checkout.getToken());
        if (!TextUtils.isEmpty(checkout.getEmail())) {
            safeCheckout.setEmail(checkout.getEmail());
        }
        safeCheckout.setShippingAddress(checkout.getShippingAddress());
        safeCheckout.setBillingAddress(checkout.getBillingAddress());
        if (checkout.getLineItems() != null) {
            safeCheckout.setLineItems(checkout.getLineItems());
        }
        if (checkout.getDiscount() != null) {
            safeCheckout.setDiscountCode(checkout.getDiscount().getCode());
        }
        safeCheckout.setShippingRate(checkout.getShippingRate());
        if (checkout.getReservationTime() != null) {
            safeCheckout.setReservationTime(checkout.getReservationTime());
        }

        return retrofitService
            .updateCheckout(new CheckoutWrapper(safeCheckout), checkout.getToken())
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>())
            .compose(new UnwrapRetrofitBodyTransformer<CheckoutWrapper, Checkout>())
            .onErrorResumeNext(new BuyClientExceptionHandler<Checkout>())
            .observeOn(callbackScheduler);
    }

    @Override
    public CancellableTask getShippingRates(final String checkoutToken, final Callback<List<ShippingRate>> callback) {
        return new CancellableTaskSubscriptionWrapper(getShippingRates(checkoutToken).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<List<ShippingRate>> getShippingRates(final String checkoutToken) {
        if (checkoutToken == null) {
            throw new NullPointerException("checkoutToken cannot be null");
        }
        if (TextUtils.isEmpty(checkoutToken)) {
            throw new IllegalArgumentException("checkoutToken cannot be empty");
        }

        int[] successCodes = {HTTP_OK};

        return retrofitService
            .getShippingRates(checkoutToken)
            .retryWhen(networkRetryPolicyProvider.provide())
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>(successCodes))
            .retryWhen(pollingRetryPolicyProvider.provide())
            .compose(new UnwrapRetrofitBodyTransformer<ShippingRatesWrapper, List<ShippingRate>>())
            .onErrorResumeNext(new BuyClientExceptionHandler<List<ShippingRate>>())
            .observeOn(callbackScheduler);
    }

    @Override
    public CancellableTask storeCreditCard(final CreditCard card, final Checkout checkout, final Callback<PaymentToken> callback) {
        return new CancellableTaskSubscriptionWrapper(storeCreditCard(card, checkout).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<PaymentToken> storeCreditCard(final CreditCard card, final Checkout checkout) {
        if (card == null) {
            throw new NullPointerException("card cannot be null");
        }

        if (checkout == null) {
            throw new NullPointerException("checkout cannot be null");
        }

        if (TextUtils.isEmpty(checkout.getToken())) {
            throw new IllegalArgumentException("checkout token cannot be empty");
        }

        PaymentSessionCheckout paymentSessionCheckout = new PaymentSessionCheckout(checkout.getToken(), card, checkout.getBillingAddress());

        final int[] successCodes = {HTTP_OK};

        return retrofitService
            .storeCreditCard(checkout.getPaymentUrl(), new PaymentSessionCheckoutWrapper(paymentSessionCheckout), BuyClientUtils.formatBasicAuthorization(apiKey))
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>(successCodes))
            .compose(new UnwrapRetrofitBodyTransformer<PaymentSession, String>())
            .map(new Func1<String, PaymentToken>() {
                @Override
                public PaymentToken call(String sessionId) {
                    return PaymentToken.createCreditCardPaymentToken(sessionId);
                }
            })
            .onErrorResumeNext(new BuyClientExceptionHandler<PaymentToken>())
            .observeOn(callbackScheduler);
    }

    @Override
    public CancellableTask completeCheckout(final PaymentToken paymentToken, final String checkoutToken, final Callback<Checkout> callback) {
        return new CancellableTaskSubscriptionWrapper(completeCheckout(paymentToken, checkoutToken).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Checkout> completeCheckout(final PaymentToken paymentToken, final String checkoutToken) {
        if (checkoutToken == null) {
            throw new NullPointerException("checkoutToken cannot be null");
        }
        if (TextUtils.isEmpty(checkoutToken)) {
            throw new IllegalArgumentException("checkout token cannot be empty");
        }

        final PaymentToken paymentTokenToSend = paymentToken != null ? paymentToken : PaymentToken.createEmptyPaymentToken();

        return retrofitService
            .completeCheckout(paymentTokenToSend, checkoutToken)
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<Response<Void>>())
            .flatMap(new Func1<Response<Void>, Observable<Checkout>>() {
                @Override
                public Observable<Checkout> call(Response<Void> voidResponse) {
                    return getCompletedCheckout(checkoutToken);
                }
            })
            .onErrorResumeNext(new BuyClientExceptionHandler<Checkout>())
            .observeOn(callbackScheduler);
    }

    private Observable<Checkout> getCompletedCheckout(final String checkoutToken) {
        return getCheckoutCompletionStatus(checkoutToken)
            .flatMap(new Func1<Boolean, Observable<Checkout>>() {
                @Override
                public Observable<Checkout> call(Boolean aBoolean) {
                    if (aBoolean) {
                        return getCheckout(checkoutToken);
                    }

                    // Poll while aBoolean == false
                    return Observable.error(new PollingRequiredException());
                }
            })
            .retryWhen(pollingRetryPolicyProvider.provide());
    }

    @Override
    public CancellableTask getCheckoutCompletionStatus(String checkoutToken, final Callback<Boolean> callback) {
        return new CancellableTaskSubscriptionWrapper(getCheckoutCompletionStatus(checkoutToken).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Boolean> getCheckoutCompletionStatus(final String checkoutToken) {
        if (checkoutToken == null) {
            throw new NullPointerException("checkoutToken cannot be null");
        }
        if (TextUtils.isEmpty(checkoutToken)) {
            throw new IllegalArgumentException("checkoutToken cannot be empty");
        }

        return retrofitService
            .getCheckoutCompletionStatus(checkoutToken)
            .retryWhen(networkRetryPolicyProvider.provide())
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>())
            .map(new Func1<Response<Void>, Boolean>() {
                     @Override
                     public Boolean call(Response<Void> voidResponse) {
                         return HTTP_OK == voidResponse.code();
                     }
                 }
            )
            .onErrorResumeNext(new BuyClientExceptionHandler<Boolean>())
            .observeOn(callbackScheduler);
    }

    public CancellableTask getCheckout(final String checkoutToken, final Callback<Checkout> callback) {
        return new CancellableTaskSubscriptionWrapper(getCheckout(checkoutToken).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Checkout> getCheckout(final String checkoutToken) {
        if (checkoutToken == null) {
            throw new NullPointerException("checkoutToken cannot be null");
        }
        if (TextUtils.isEmpty(checkoutToken)) {
            throw new IllegalArgumentException("checkoutToken cannot be empty");
        }

        return retrofitService
            .getCheckout(checkoutToken)
            .retryWhen(networkRetryPolicyProvider.provide())
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>())
            .compose(new UnwrapRetrofitBodyTransformer<CheckoutWrapper, Checkout>())
            .onErrorResumeNext(new BuyClientExceptionHandler<Checkout>())
            .observeOn(callbackScheduler);
    }

    @Override
    public CancellableTask applyGiftCard(final String giftCardCode, final Checkout checkout, final Callback<Checkout> callback) {
        return new CancellableTaskSubscriptionWrapper(applyGiftCard(giftCardCode, checkout).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Checkout> applyGiftCard(final String giftCardCode, final Checkout checkout) {
        if (giftCardCode == null) {
            throw new NullPointerException("giftCardCode cannot be null");
        }
        if (TextUtils.isEmpty(giftCardCode)) {
            throw new IllegalArgumentException("giftCardCode cannot be empty");
        }
        if (checkout == null) {
            throw new NullPointerException("checkout cannot be null");
        }
        if (TextUtils.isEmpty(checkout.getToken())) {
            throw new IllegalArgumentException("checkout token cannot be empty");
        }

        final Checkout safeCheckout = checkout.copy();
        final GiftCard giftCard = new GiftCard(giftCardCode);
        return retrofitService
            .applyGiftCard(new GiftCardWrapper(giftCard), checkout.getToken())
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>())
            .compose(new UnwrapRetrofitBodyTransformer<GiftCardWrapper, GiftCard>())
            .map(new Func1<GiftCard, Checkout>() {
                @Override
                public Checkout call(GiftCard giftCard) {
                    if (giftCard != null) {
                        safeCheckout.addGiftCard(giftCard);
                        safeCheckout.setPaymentDue(giftCard.getCheckout().getPaymentDue());
                    }
                    return safeCheckout;
                }
            })
            .onErrorResumeNext(new BuyClientExceptionHandler<Checkout>())
            .observeOn(callbackScheduler);
    }

    @Override
    public CancellableTask removeGiftCard(final Long giftCardId, final Checkout checkout, final Callback<Checkout> callback) {
        return new CancellableTaskSubscriptionWrapper(removeGiftCard(giftCardId, checkout).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Checkout> removeGiftCard(final Long giftCardId, final Checkout checkout) {
        if (checkout == null) {
            throw new NullPointerException("checkout cannot be null");
        }

        if (TextUtils.isEmpty(checkout.getToken())) {
            throw new IllegalArgumentException("checkout token cannot be empty");
        }

        if (giftCardId == null) {
            throw new NullPointerException("giftCard cannot be null");
        }

        final Checkout safeCheckout = checkout.copy();

        return retrofitService
            .removeGiftCard(giftCardId, safeCheckout.getToken())
            .doOnNext(new RetrofitSuccessHttpStatusCodeHandler<>())
            .compose(new UnwrapRetrofitBodyTransformer<GiftCardWrapper, GiftCard>())
            .map(new Func1<GiftCard, Checkout>() {
                @Override
                public Checkout call(GiftCard giftCard) {
                    if (giftCard != null) {
                        safeCheckout.removeGiftCard(giftCard);
                        safeCheckout.setPaymentDue(giftCard.getCheckout().getPaymentDue());
                    }
                    return safeCheckout;
                }
            })
            .onErrorResumeNext(new BuyClientExceptionHandler<Checkout>())
            .observeOn(callbackScheduler);
    }

    @Override
    public CancellableTask removeProductReservationsFromCheckout(final String checkoutToken, final Callback<Checkout> callback) {
        return new CancellableTaskSubscriptionWrapper(removeProductReservationsFromCheckout(checkoutToken).subscribe(new InternalCallbackSubscriber<>(callback)));
    }

    @Override
    public Observable<Checkout> removeProductReservationsFromCheckout(final String checkoutToken) {
        if (checkoutToken == null) {
            throw new NullPointerException("checkoutToken cannot be null");
        }
        if (TextUtils.isEmpty(checkoutToken)) {
            throw new IllegalArgumentException("checkoutToken cannot be empty");
        }

        final Checkout expiredCheckout = new Checkout(checkoutToken);
        expiredCheckout.setReservationTime(0);

        return updateCheckout(expiredCheckout);
    }
}
