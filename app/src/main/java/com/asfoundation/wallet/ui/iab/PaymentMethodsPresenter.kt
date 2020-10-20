package com.asfoundation.wallet.ui.iab

import android.os.Bundle
import com.appcoins.wallet.bdsbilling.repository.BillingSupportedType
import com.appcoins.wallet.bdsbilling.repository.entity.Purchase
import com.appcoins.wallet.bdsbilling.repository.entity.Transaction
import com.appcoins.wallet.billing.BillingMessagesMapper
import com.appcoins.wallet.gamification.repository.ForecastBonusAndLevel
import com.appcoins.wallet.gamification.repository.GamificationStats
import com.appcoins.wallet.gamification.repository.Levels
import com.asf.wallet.R
import com.asfoundation.wallet.analytics.AmplitudeAnalytics
import com.asfoundation.wallet.analytics.AnalyticsSetup
import com.asfoundation.wallet.billing.adyen.PaymentType
import com.asfoundation.wallet.billing.analytics.BillingAnalytics
import com.asfoundation.wallet.entity.TransactionBuilder
import com.asfoundation.wallet.logging.Logger
import com.asfoundation.wallet.ui.PaymentNavigationData
import com.asfoundation.wallet.ui.gamification.GamificationMapper
import com.asfoundation.wallet.util.CurrencyFormatUtils
import com.asfoundation.wallet.util.WalletCurrency
import com.asfoundation.wallet.util.isNoNetworkException
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Function3
import retrofit2.HttpException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import java.util.concurrent.TimeUnit

class PaymentMethodsPresenter(
    private val view: PaymentMethodsView,
    private val viewScheduler: Scheduler,
    private val networkThread: Scheduler,
    private val disposables: CompositeDisposable,
    private val billingMessagesMapper: BillingMessagesMapper,
    private val analytics: BillingAnalytics,
    private val analyticsSetup: AnalyticsSetup,
    private val amplitudeAnalytics: AmplitudeAnalytics,
    private val transaction: TransactionBuilder,
    private val paymentMethodsMapper: PaymentMethodsMapper,
    private val formatter: CurrencyFormatUtils,
    private val logger: Logger,
    private val paymentMethodsInteract: PaymentMethodsInteract,
    private val activity: IabView?,
    private val gamificationMapper: GamificationMapper,
    private val paymentMethodsData: PaymentMethodsData) {

  private var cachedGamificationLevel = 0
  private var closeToLevelUp: Boolean = false
  private var shouldHandlePreselected = true
  private var hasStartedAuth = false

  companion object {
    private val TAG = PaymentMethodsPresenter::class.java.name
    private const val GAMIFICATION_LEVEL = "gamification_level"
    private const val HAS_STARTED_AUTH = "has_started_auth"
  }

  fun present(savedInstanceState: Bundle?) {
    savedInstanceState?.let {
      cachedGamificationLevel = savedInstanceState.getInt(GAMIFICATION_LEVEL)
      hasStartedAuth = savedInstanceState.getBoolean(HAS_STARTED_AUTH)
    }
    handleOnGoingPurchases()
    handleCancelClick()
    handleErrorDismisses()
    handleMorePaymentMethodClicks()
    handleBuyClick()
    handleSupportClicks()
    handleAuthenticationResult()
    if (paymentMethodsData.isBds) handlePaymentSelection()
  }

  fun onResume(firstRun: Boolean) = setupUi(firstRun)

  private fun handlePaymentSelection() {
    disposables.add(view.getPaymentSelection()
        .observeOn(viewScheduler)
        .doOnNext { selectedPaymentMethod ->
          if (paymentMethodsInteract.isBonusActiveAndValid()) {
            handleBonusVisibility(selectedPaymentMethod)
          }
          handlePositiveButtonText(selectedPaymentMethod)
        }
        .subscribe({}, { it.printStackTrace() }))
  }

  private fun handleBuyClick() {
    disposables.add(view.getBuyClick()
        .observeOn(viewScheduler)
        .doOnNext { handleBuyAnalytics(it) }
        .doOnNext { selectedPaymentMethod ->
          when (paymentMethodsMapper.map(selectedPaymentMethod.id)) {
            PaymentMethodsView.SelectedPaymentMethod.EARN_APPC -> view.showEarnAppcoins()
            PaymentMethodsView.SelectedPaymentMethod.APPC_CREDITS -> handleWalletBlockStatus(
                selectedPaymentMethod)
            PaymentMethodsView.SelectedPaymentMethod.MERGED_APPC -> {
              view.showMergedAppcoins(cachedGamificationLevel)
            }
            else -> {
              if (paymentMethodsInteract.hasAuthenticationPermission()) {
                view.showAuthenticationActivity(selectedPaymentMethod, cachedGamificationLevel,
                    false)
              } else {
                when (paymentMethodsMapper.map(selectedPaymentMethod.id)) {
                  PaymentMethodsView.SelectedPaymentMethod.PAYPAL -> view.showPaypal(
                      cachedGamificationLevel)
                  PaymentMethodsView.SelectedPaymentMethod.CREDIT_CARD -> view.showCreditCard(
                      cachedGamificationLevel)
                  PaymentMethodsView.SelectedPaymentMethod.APPC -> view.showAppCoins(
                      cachedGamificationLevel)
                  PaymentMethodsView.SelectedPaymentMethod.SHARE_LINK -> view.showShareLink(
                      selectedPaymentMethod.id)
                  PaymentMethodsView.SelectedPaymentMethod.LOCAL_PAYMENTS -> view.showLocalPayment(
                      selectedPaymentMethod.id, selectedPaymentMethod.iconUrl,
                      selectedPaymentMethod.label, cachedGamificationLevel)
                  else -> return@doOnNext
                }
              }
            }
          }
        }
        .subscribe({}, { it.printStackTrace() }))
  }

  private fun handleAuthenticationResult() {
    disposables.add(activity!!.onAuthenticationResult()
        .observeOn(viewScheduler)
        .doOnNext {
          if (it.paymentNavigationData == null) close()
          else if (!it.isSuccess) {
            hasStartedAuth = false
            view.hideLoading()
            if (it.paymentNavigationData.isPreselected && paymentMethodsMapper.map(
                    it.paymentNavigationData.selectedPaymentId) == PaymentMethodsView.SelectedPaymentMethod.CREDIT_CARD) {
              close()
            }
          } else {
            navigateToPayment(it.paymentNavigationData)
          }
        }
        .subscribe({}, { it.printStackTrace() }))
  }

  private fun handleWalletBlockStatus(selectedPaymentMethod: PaymentMethod) {
    disposables.add(paymentMethodsInteract.isWalletBlocked()
        .subscribeOn(networkThread)
        .observeOn(viewScheduler)
        .doOnSuccess {
          if (paymentMethodsInteract.hasAuthenticationPermission()) {
            view.showAuthenticationActivity(selectedPaymentMethod, cachedGamificationLevel, false)
          } else {
            view.showCredits(cachedGamificationLevel)
          }
        }
        .doOnSubscribe { view.showProgressBarLoading() }
        .doOnError { showError(it) }
        .subscribe({}, { showError(it) })
    )
  }

  private fun handleOnGoingPurchases() {
    if (transaction.skuId == null) {
      disposables.add(isSetupCompleted()
          .doOnComplete { view.hideLoading() }
          .subscribeOn(viewScheduler)
          .subscribe({}, { it.printStackTrace() }))
      return
    }
    disposables.add(waitForUi(transaction.skuId)
        .observeOn(viewScheduler)
        .doOnComplete { view.hideLoading() }
        .subscribe({ }, { showError(it) }))
  }

  private fun navigateToPayment(paymentNavigationData: PaymentNavigationData) {
    when (paymentMethodsMapper.map(paymentNavigationData.selectedPaymentId)) {
      PaymentMethodsView.SelectedPaymentMethod.PAYPAL -> view.showPaypal(
          paymentNavigationData.gamificationLevel)
      PaymentMethodsView.SelectedPaymentMethod.CREDIT_CARD -> {
        if (paymentNavigationData.isPreselected) {
          view.showAdyen(paymentNavigationData.fiatAmount,
              paymentNavigationData.fiatCurrency, PaymentType.CARD,
              paymentNavigationData.selectedPaymentIcon, paymentNavigationData.gamificationLevel)
        } else view.showCreditCard(paymentNavigationData.gamificationLevel)
      }
      PaymentMethodsView.SelectedPaymentMethod.APPC -> view.showAppCoins(
          paymentNavigationData.gamificationLevel)
      PaymentMethodsView.SelectedPaymentMethod.APPC_CREDITS -> view.showCredits(
          paymentNavigationData.gamificationLevel)
      PaymentMethodsView.SelectedPaymentMethod.SHARE_LINK -> view.showShareLink(
          paymentNavigationData.selectedPaymentId)
      PaymentMethodsView.SelectedPaymentMethod.LOCAL_PAYMENTS -> {
        view.showLocalPayment(paymentNavigationData.selectedPaymentId,
            paymentNavigationData.selectedPaymentIcon!!,
            paymentNavigationData.selectedPaymentLabel!!,
            paymentNavigationData.gamificationLevel)
      }
      else -> {
        view.showError(R.string.unknown_error)
        logger.log(TAG, "Wrong payment method after authentication.")
      }
    }
  }


  private fun isSetupCompleted(): Completable {
    return view.setupUiCompleted()
        .takeWhile { isViewSet -> !isViewSet }
        .ignoreElements()
  }

  private fun waitForUi(skuId: String?): Completable {
    return Completable.mergeArray(checkProcessing(skuId), checkAndConsumePrevious(skuId),
        isSetupCompleted())
  }

  private fun checkProcessing(skuId: String?): Completable {
    return paymentMethodsInteract.getSkuTransaction(paymentMethodsData.appPackage, skuId,
        networkThread)
        .subscribeOn(networkThread)
        .filter { (_, status) -> status === Transaction.Status.PROCESSING }
        .observeOn(viewScheduler)
        .doOnSuccess { view.showProcessingLoadingDialog() }
        .doOnSuccess { handleProcessing() }
        .map { it.uid }
        .observeOn(networkThread)
        .flatMapCompletable { uid ->
          paymentMethodsInteract.checkTransactionStateFromTransactionId(uid)
              .ignoreElements()
              .andThen(finishProcess(skuId))
        }
  }

  private fun handleProcessing() {
    disposables.add(
        paymentMethodsInteract.getCurrentPaymentStep(paymentMethodsData.appPackage, transaction)
            .filter { currentPaymentStep -> currentPaymentStep == AsfInAppPurchaseInteractor.CurrentPaymentStep.PAUSED_ON_CHAIN }
            .doOnSuccess {
              view.lockRotation()
              paymentMethodsInteract.resume(paymentMethodsData.uri,
                  AsfInAppPurchaseInteractor.TransactionType.NORMAL,
                  paymentMethodsData.appPackage, transaction.skuId,
                  paymentMethodsData.developerPayload, paymentMethodsData.isBds)
            }
            .subscribe({}, { it.printStackTrace() }))
  }

  private fun finishProcess(skuId: String?): Completable {
    return paymentMethodsInteract.getSkuPurchase(paymentMethodsData.appPackage, skuId,
        networkThread)
        .observeOn(viewScheduler)
        .doOnSuccess { purchase -> finish(purchase, false) }
        .ignoreElement()
  }

  private fun checkAndConsumePrevious(sku: String?): Completable {
    return getPurchases()
        .subscribeOn(networkThread)
        .observeOn(viewScheduler)
        .flatMapCompletable { purchases ->
          Completable.fromAction {
            if (hasRequestedSkuPurchase(purchases, sku)) view.showItemAlreadyOwnedError()
          }
        }
  }

  private fun setupUi(firstRun: Boolean) {
    disposables.add(
        paymentMethodsInteract.convertToLocalFiat(paymentMethodsData.transactionValue.toDouble())
            .subscribeOn(networkThread)
            .flatMapCompletable { fiatValue ->
              getPaymentMethods(fiatValue)
                  .flatMapCompletable { paymentMethods ->
                    Single.zip(paymentMethodsInteract.getEarningBonus(transaction.domain,
                        transaction.amount()), paymentMethodsInteract.getUserStatus(),
                        paymentMethodsInteract.getLevels(),
                        Function3 { earningBonus: ForecastBonusAndLevel, userStats: GamificationStats, levels: Levels ->
                          Triple(earningBonus, userStats, levels)
                        })
                        .observeOn(viewScheduler)
                        .flatMapCompletable {
                          Completable.fromAction {
                            setupBonusInformation(it.first)
                            if (paymentMethodsInteract.isBonusActiveAndValid(it.first)) {
                              setUpNextLevelInformation(it.second, it.third,
                                  paymentMethodsData.transactionValue)
                            }
                            if (shouldHandlePreselected) {
                              selectPaymentMethod(paymentMethods, fiatValue,
                                  paymentMethodsInteract.isBonusActiveAndValid(it.first))
                              shouldHandlePreselected = false
                            }
                          }
                        }
                  }
            }
            .subscribeOn(networkThread)
            .observeOn(viewScheduler)
            .doOnComplete {
              //If not first run we should rely on the hideLoading of the handleOnGoingPurchases method
              if (!firstRun) view.hideLoading()
            }
            .subscribe({ }, { this.showError(it) }))
  }

  private fun setUpNextLevelInformation(userStats: GamificationStats, levels: Levels,
                                        transactionValue: BigDecimal) {
    val progress = getProgressPercentage(userStats, levels.list)
    if (shouldShowNextLevel(levels, progress, userStats)) {
      closeToLevelUp = true
      val currentLevelInfo = gamificationMapper.mapCurrentLevelInfo(cachedGamificationLevel)
      val nextLevelInfo = gamificationMapper.mapCurrentLevelInfo(cachedGamificationLevel + 1)
      view.setLevelUpInformation(cachedGamificationLevel, progress,
          gamificationMapper.getRectangleGamificationBackground(currentLevelInfo.levelColor),
          gamificationMapper.getRectangleGamificationBackground(nextLevelInfo.levelColor),
          currentLevelInfo.levelColor, willLevelUp(userStats, transactionValue),
          userStats.nextLevelAmount!!.minus(userStats.totalSpend))
    } else {
      closeToLevelUp = false
    }
  }

  private fun willLevelUp(userStats: GamificationStats, transactionValue: BigDecimal): Boolean {
    return userStats.totalSpend + transactionValue >= userStats.nextLevelAmount
  }

  private fun shouldShowNextLevel(levels: Levels, progress: Double,
                                  userStats: GamificationStats): Boolean {
    return gamificationMapper.mapLevelUpPercentage(cachedGamificationLevel) <= progress &&
        cachedGamificationLevel < levels.list.size - 1 && levels.status == Levels.Status.OK && userStats.status == GamificationStats.Status.OK && userStats.nextLevelAmount != null
  }

  private fun getProgressPercentage(userStats: GamificationStats,
                                    list: List<Levels.Level>): Double {
    return if (cachedGamificationLevel <= list.size - 1) {
      var levelRange = userStats.nextLevelAmount?.minus(list[cachedGamificationLevel].amount)
      if (levelRange?.toDouble() == 0.0) {
        levelRange = BigDecimal.ONE
      }
      val amountSpentInLevel = userStats.totalSpend - list[cachedGamificationLevel].amount
      amountSpentInLevel.divide(levelRange, 2, RoundingMode.HALF_EVEN)
          .multiply(BigDecimal(100))
          .toDouble()
    } else 0.0
  }


  private fun setupBonusInformation(forecastBonus: ForecastBonusAndLevel) {
    if (paymentMethodsInteract.isBonusActiveAndValid(forecastBonus)) {
      view.setBonus(forecastBonus.amount, forecastBonus.currency)
    } else {
      view.removeBonus()
    }
    cachedGamificationLevel = forecastBonus.level
    analyticsSetup.setGamificationLevel(cachedGamificationLevel)
    amplitudeAnalytics.setGamificationLevel(cachedGamificationLevel)
  }

  private fun selectPaymentMethod(paymentMethods: List<PaymentMethod>, fiatValue: FiatValue,
                                  isBonusActive: Boolean) {
    val fiatAmount = formatter.formatCurrency(fiatValue.amount, WalletCurrency.FIAT)
    val appcAmount = formatter.formatCurrency(transaction.amount(), WalletCurrency.APPCOINS)
    if (paymentMethodsInteract.hasAsyncLocalPayment()) {
      //After a asynchronous payment credits will be used as pre selected
      getCreditsPaymentMethod(paymentMethods)?.let {
        if (it.isEnabled) {
          showPreSelectedPaymentMethod(fiatValue, it, fiatAmount, appcAmount, isBonusActive)
          return
        }
      }
    }

    if (paymentMethodsInteract.hasPreSelectedPaymentMethod()) {
      val paymentMethod = getPreSelectedPaymentMethod(paymentMethods)
      if (paymentMethod == null || !paymentMethod.isEnabled) {
        showPaymentMethods(fiatValue, paymentMethods,
            PaymentMethodsView.PaymentMethodId.CREDIT_CARD.id, fiatAmount, appcAmount)
      } else {
        when (paymentMethod.id) {
          PaymentMethodsView.PaymentMethodId.CREDIT_CARD.id -> {
            analytics.sendPurchaseDetailsEvent(paymentMethodsData.appPackage, transaction.skuId,
                transaction.amount()
                    .toString(), transaction.type)
            if (paymentMethodsInteract.hasAuthenticationPermission()) {
              if (!hasStartedAuth) {
                view.showAuthenticationActivity(paymentMethod, cachedGamificationLevel, true,
                    fiatValue)
                hasStartedAuth = true
              }
            } else {
              view.showAdyen(fiatValue.amount, fiatValue.currency, PaymentType.CARD,
                  paymentMethod.iconUrl, cachedGamificationLevel)
            }
          }
          else -> showPreSelectedPaymentMethod(fiatValue, paymentMethod, fiatAmount, appcAmount,
              isBonusActive)
        }
      }
    } else {
      val paymentMethodId = getLastUsedPaymentMethod(paymentMethods)
      showPaymentMethods(fiatValue, paymentMethods, paymentMethodId, fiatAmount, appcAmount)
    }
  }

  private fun getCreditsPaymentMethod(paymentMethods: List<PaymentMethod>): PaymentMethod? {
    paymentMethods.forEach {
      if (it.id == PaymentMethodsView.PaymentMethodId.MERGED_APPC.id) {
        val mergedPaymentMethod = it as AppCoinsPaymentMethod
        return PaymentMethod(PaymentMethodsView.PaymentMethodId.APPC_CREDITS.id,
            mergedPaymentMethod.creditsLabel, mergedPaymentMethod.iconUrl, mergedPaymentMethod.fee,
            mergedPaymentMethod.isCreditsEnabled)
      }
      if (it.id == PaymentMethodsView.PaymentMethodId.APPC_CREDITS.id) {
        return it
      }
    }

    return null
  }

  private fun showPaymentMethods(fiatValue: FiatValue, paymentMethods: List<PaymentMethod>,
                                 paymentMethodId: String, fiatAmount: String, appcAmount: String) {
    var appcEnabled = false
    var creditsEnabled = false
    val paymentList: MutableList<PaymentMethod>
    val symbol = mapCurrencyCodeToSymbol(fiatValue.currency)
    if (paymentMethodsData.isBds) {
      paymentMethods.forEach {
        if (it is AppCoinsPaymentMethod) {
          appcEnabled = it.isAppcEnabled
          creditsEnabled = it.isCreditsEnabled
        }
      }
      paymentList = paymentMethods.toMutableList()
    } else {
      paymentList = paymentMethods
          .filter {
            it.id == paymentMethodsMapper.map(PaymentMethodsView.SelectedPaymentMethod.APPC)
          }
          .toMutableList()
    }
    view.showPaymentMethods(paymentList, fiatValue, symbol, paymentMethodId, fiatAmount, appcAmount,
        appcEnabled, creditsEnabled)
  }

  private fun showPreSelectedPaymentMethod(fiatValue: FiatValue, paymentMethod: PaymentMethod,
                                           fiatAmount: String, appcAmount: String,
                                           isBonusActive: Boolean) {
    view.showPreSelectedPaymentMethod(paymentMethod, fiatValue,
        mapCurrencyCodeToSymbol(fiatValue.currency), fiatAmount, appcAmount, isBonusActive)
  }

  private fun mapCurrencyCodeToSymbol(currencyCode: String): String {
    return if (currencyCode.equals("APPC", ignoreCase = true))
      currencyCode
    else
      Currency.getInstance(currencyCode).currencyCode
  }

  private fun handleCancelClick() {
    disposables.add(view.getCancelClick()
        .observeOn(networkThread)
        .doOnNext { handlePaymentMethodAnalytics(it) }
        .subscribe { close() })
  }

  private fun handlePaymentMethodAnalytics(paymentMethod: PaymentMethod) {
    if (paymentMethodsInteract.hasPreSelectedPaymentMethod()) {
      analytics.sendPreSelectedPaymentMethodEvent(paymentMethodsData.appPackage, transaction.skuId,
          transaction.amount()
              .toString(), paymentMethod.id, transaction.type, "cancel")
    } else {
      analytics.sendPaymentMethodEvent(paymentMethodsData.appPackage, transaction.skuId,
          transaction.amount()
              .toString(), paymentMethod.id, transaction.type, "cancel")
    }
  }

  private fun handleMorePaymentMethodClicks() {
    disposables.add(view.getMorePaymentMethodsClicks()
        .observeOn(networkThread)
        .doOnNext { selectedPaymentMethod ->
          analytics.sendPreSelectedPaymentMethodEvent(paymentMethodsData.appPackage,
              transaction.skuId,
              transaction.amount()
                  .toString(), selectedPaymentMethod.id, transaction.type, "other_payments")
        }
        .observeOn(viewScheduler)
        .doOnEach { view.showSkeletonLoading() }
        .flatMapSingle {
          paymentMethodsInteract.convertToLocalFiat(paymentMethodsData.transactionValue.toDouble())
              .subscribeOn(networkThread)
        }
        .flatMapCompletable { fiatValue ->
          getPaymentMethods(fiatValue).observeOn(viewScheduler)
              .flatMapCompletable { paymentMethods ->
                Completable.fromAction {
                  val fiatAmount = formatter.formatCurrency(fiatValue.amount, WalletCurrency.FIAT)
                  val appcAmount = formatter.formatCurrency(transaction.amount(),
                      WalletCurrency.APPCOINS)
                  val paymentMethodId = getLastUsedPaymentMethod(paymentMethods)
                  showPaymentMethods(fiatValue, paymentMethods, paymentMethodId, fiatAmount,
                      appcAmount)
                }
              }
              .andThen(
                  Completable.fromAction { paymentMethodsInteract.removePreSelectedPaymentMethod() })
              .andThen(Completable.fromAction { paymentMethodsInteract.removeAsyncLocalPayment() })
              .andThen(Completable.fromAction { view.hideLoading() })
        }
        .subscribe({ }, { this.showError(it) }))
  }

  private fun showError(t: Throwable) {
    t.printStackTrace()
    logger.log(TAG, t)
    when {
      t.isNoNetworkException() -> view.showError(R.string.notification_no_network_poa)
      isItemAlreadyOwnedError(t) -> view.showItemAlreadyOwnedError()
      else -> view.showError(R.string.activity_iab_error_message)
    }
  }

  private fun isItemAlreadyOwnedError(throwable: Throwable): Boolean {
    return throwable is HttpException && throwable.code() == 409
  }

  private fun close() {
    view.close(billingMessagesMapper.mapCancellation())
  }

  private fun handleErrorDismisses() {
    disposables.add(Observable.merge(view.errorDismisses(), view.onBackPressed())
        .flatMapCompletable { itemAlreadyOwned ->
          if (itemAlreadyOwned) {
            getPurchases().doOnSuccess { purchases ->
              val purchase = getRequestedSkuPurchase(purchases, transaction.skuId)
              purchase?.let { finish(it, itemAlreadyOwned) } ?: view.close(Bundle())
            }
                .ignoreElement()
          } else {
            return@flatMapCompletable Completable.fromAction { view.close(Bundle()) }
          }
        }
        .subscribe({ }, { view.close(Bundle()) }))
  }

  private fun handleSupportClicks() {
    disposables.add(Observable.merge(view.getSupportIconClicks(), view.getSupportLogoClicks())
        .throttleFirst(50, TimeUnit.MILLISECONDS)
        .observeOn(viewScheduler)
        .flatMapCompletable { paymentMethodsInteract.showSupport(cachedGamificationLevel) }
        .subscribe({}, { it.printStackTrace() })
    )
  }

  private fun finish(purchase: Purchase, itemAlreadyOwned: Boolean) {
    view.finish(billingMessagesMapper.mapFinishedPurchase(purchase, itemAlreadyOwned))
  }

  fun sendPaymentMethodsEvents() {
    analytics.sendPurchaseDetailsEvent(paymentMethodsData.appPackage, transaction.skuId,
        transaction.amount()
            .toString(), transaction.type)
  }

  fun sendPreSelectedPaymentMethodsEvents() {
    analytics.sendPurchaseDetailsEvent(paymentMethodsData.appPackage, transaction.skuId,
        transaction.amount()
            .toString(), transaction.type)
  }

  fun stop() {
    disposables.clear()
  }

  private fun getPaymentMethods(fiatValue: FiatValue): Single<List<PaymentMethod>> {
    return if (paymentMethodsData.isBds) {
      paymentMethodsInteract.getPaymentMethods(transaction, fiatValue.amount.toString(),
          fiatValue.currency)
          .map { paymentMethodsInteract.mergeAppcoins(it) }
          .map { paymentMethodsInteract.swapDisabledPositions(it) }
          .doOnSuccess { updateBalanceDao() }
    } else {
      Single.just(listOf(PaymentMethod.APPC))
    }
  }

  //Updates database with the latest balance to take less time loading the merged appcoins view
  private fun updateBalanceDao() {
    disposables.add(
        Observable.zip(paymentMethodsInteract.getEthBalance(),
            paymentMethodsInteract.getCreditsBalance(),
            paymentMethodsInteract.getAppcBalance(), Function3 { _: Any, _: Any, _: Any -> })
            .take(1)
            .subscribeOn(networkThread)
            .subscribe({}, { it.printStackTrace() }))
  }

  private fun getPreSelectedPaymentMethod(paymentMethods: List<PaymentMethod>): PaymentMethod? {
    val preSelectedPreference = paymentMethodsInteract.getPreSelectedPaymentMethod()
    for (paymentMethod in paymentMethods) {
      if (paymentMethod.id == PaymentMethodsView.PaymentMethodId.MERGED_APPC.id) {
        if (preSelectedPreference == PaymentMethodsView.PaymentMethodId.APPC.id) {
          val mergedPaymentMethod = paymentMethod as AppCoinsPaymentMethod
          return PaymentMethod(PaymentMethodsView.PaymentMethodId.APPC.id,
              mergedPaymentMethod.appcLabel, mergedPaymentMethod.iconUrl, mergedPaymentMethod.fee,
              mergedPaymentMethod.isAppcEnabled)
        }
        if (preSelectedPreference == PaymentMethodsView.PaymentMethodId.APPC_CREDITS.id) {
          val mergedPaymentMethod = paymentMethod as AppCoinsPaymentMethod
          return PaymentMethod(PaymentMethodsView.PaymentMethodId.APPC_CREDITS.id,
              mergedPaymentMethod.creditsLabel, paymentMethod.creditsIconUrl,
              mergedPaymentMethod.fee, mergedPaymentMethod.isCreditsEnabled)
        }
      }
      if (paymentMethod.id == preSelectedPreference) {
        return paymentMethod
      }
    }
    return null
  }

  private fun getLastUsedPaymentMethod(paymentMethods: List<PaymentMethod>): String {
    val lastUsedPaymentMethod = paymentMethodsInteract.getLastUsedPaymentMethod()
    for (it in paymentMethods) {
      if (it.isEnabled) {
        if (it.id == PaymentMethodsView.PaymentMethodId.MERGED_APPC.id &&
            (lastUsedPaymentMethod == PaymentMethodsView.PaymentMethodId.APPC.id ||
                lastUsedPaymentMethod == PaymentMethodsView.PaymentMethodId.APPC_CREDITS.id)) {
          return PaymentMethodsView.PaymentMethodId.MERGED_APPC.id
        }
        if (it.id == lastUsedPaymentMethod) {
          return it.id
        }
      }
    }
    return PaymentMethodsView.PaymentMethodId.CREDIT_CARD.id
  }

  private fun handleBonusVisibility(selectedPaymentMethod: String) {
    when (selectedPaymentMethod) {
      paymentMethodsMapper.map(PaymentMethodsView.SelectedPaymentMethod.EARN_APPC) -> {
        view.replaceBonus()
        view.hideLevelUp()
      }
      paymentMethodsMapper
          .map(PaymentMethodsView.SelectedPaymentMethod.MERGED_APPC) -> {
        view.hideBonus()
        view.hideLevelUp()
      }
      paymentMethodsMapper
          .map(PaymentMethodsView.SelectedPaymentMethod.APPC_CREDITS) -> {
        view.hideBonus()
        view.hideLevelUp()
      }
      else -> {
        if (closeToLevelUp) view.showLevelUp()
        else view.showBonus()
      }
    }
  }

  private fun handlePositiveButtonText(selectedPaymentMethod: String) {
    if (selectedPaymentMethod == paymentMethodsMapper.map(
            PaymentMethodsView.SelectedPaymentMethod.MERGED_APPC) || selectedPaymentMethod == paymentMethodsMapper.map(
            PaymentMethodsView.SelectedPaymentMethod.EARN_APPC)) {
      view.showNext()
    } else {
      view.showBuy()
    }
  }

  private fun handleBuyAnalytics(selectedPaymentMethod: PaymentMethod) {
    val action =
        if (selectedPaymentMethod.id == PaymentMethodsView.PaymentMethodId.MERGED_APPC.id) "next" else "buy"
    if (paymentMethodsInteract.hasPreSelectedPaymentMethod()) {
      analytics.sendPreSelectedPaymentMethodEvent(paymentMethodsData.appPackage, transaction.skuId,
          transaction.amount()
              .toString(), selectedPaymentMethod.id, transaction.type, action)
    } else {
      analytics.sendPaymentMethodEvent(paymentMethodsData.appPackage, transaction.skuId,
          transaction.amount()
              .toString(), selectedPaymentMethod.id, transaction.type, action)
    }
  }

  private fun getPurchases(): Single<List<Purchase>> {
    return paymentMethodsInteract.getPurchases(paymentMethodsData.appPackage,
        BillingSupportedType.INAPP,
        networkThread)
  }

  private fun hasRequestedSkuPurchase(purchases: List<Purchase>, sku: String?): Boolean {
    for (purchase in purchases) {
      if (purchase.product.name == sku) {
        return true
      }
    }
    return false
  }

  private fun getRequestedSkuPurchase(purchases: List<Purchase>, sku: String?): Purchase? {
    for (purchase in purchases) {
      if (purchase.product.name == sku) {
        return purchase
      }
    }
    return null
  }

  fun onSavedInstance(outState: Bundle) {
    outState.putInt(GAMIFICATION_LEVEL, cachedGamificationLevel)
    outState.putBoolean(HAS_STARTED_AUTH, hasStartedAuth)
  }
}
