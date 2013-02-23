package net.fwbrasil.radon.transaction

import net.fwbrasil.radon.RetryLimitTransactionException
import net.fwbrasil.radon.RadonContext
import net.fwbrasil.radon.RequiredTransactionException
import net.fwbrasil.radon.RetryWithWriteTransactionException
import net.fwbrasil.radon.ConcurrentTransactionException
import net.fwbrasil.radon.util.ExclusiveThreadLocal
import net.fwbrasil.radon.util.Debug

class TransactionManager(implicit val context: TransactionContext) {

    private[this] val activeTransactionThreadLocal =
        new ExclusiveThreadLocal[Transaction]

    private[radon] def isActive(transaction: Option[Transaction]) =
        getActiveTransaction != None && getActiveTransaction == transaction

    private[radon] def activate(transaction: Option[Transaction]) = {
        val active = getActiveTransaction
        if ((getActiveTransaction != None || transaction == None)
            && getActiveTransaction != transaction)
            throw new IllegalStateException("Another transaction is active.")
        activeTransactionThreadLocal.set(transaction)
    }

    private[radon] def deactivate(transaction: Option[Transaction]) = {
        val active = getActiveTransaction
        if (active != transaction)
            throw new IllegalStateException("Transaction is not active.")
        activeTransactionThreadLocal.clean(transaction)
    }

    private[fwbrasil] def getRequiredActiveTransaction: Transaction = {
        val active = getActiveTransaction
        if (active != None)
            active.get
        else
            throw new RequiredTransactionException
    }

    private[fwbrasil] def getActiveTransaction =
        activeTransactionThreadLocal.get

    private[radon] def runInTransaction[A](transaction: Transaction)(f: => A): A = {
        val someTransaction = Some(transaction)
        activate(someTransaction)
        val res =
            try
                f
            catch {
                case e: Throwable => {
                    deactivate(Option(transaction))
                    transaction.rollback
                    throw e
                }
            }
        deactivate(someTransaction)
        res
    }

    private[radon] def runInNewTransactionWithRetry[A](f: => A): A =
        runInTransactionWithRetry(new Transaction)(f)

    protected def waitToRetry(e: ConcurrentTransactionException) =
        Thread.sleep(context.milisToWaitBeforeRetry)

    private[radon] def runInTransactionWithRetry[A](transaction: Transaction)(f: => A): A = {
        var retryCount = 0
        def retry: A = {
            retryCount += 1
            transaction.clear
            if (retryCount < context.retryLimit)
                attemptTransact
            else
                throw new RetryLimitTransactionException
        }
        def attemptTransact: A = {
            try {
                val result = runInTransaction(transaction)(f)
                transaction.commit
                result
            } catch {
                case e: ConcurrentTransactionException =>
                    waitToRetry(e)
                    transaction.isRetryWithWrite = e.retryWithWrite
                    retry
            }
        }
        attemptTransact
    }

}