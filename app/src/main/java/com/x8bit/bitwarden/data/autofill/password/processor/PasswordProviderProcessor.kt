package com.x8bit.bitwarden.data.autofill.password.processor

import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import java.util.concurrent.atomic.AtomicInteger


/**
 * A class to handle Password credential request processing. This includes save and autofill requests.
 */
interface PasswordProviderProcessor {

    /**
     * Process the [BeginCreateCredentialRequest] and invoke the [callback] with the result.
     *
     * @param request The request data from the OS that contains data about the requesting provider.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     * this to notify us that the result is no longer needed and we should stop handling it in order
     * to save our resources.
     * @param callback the callback object to be used to notify the response or error
     */
    suspend fun processCreateCredentialRequest(
        requestCode: AtomicInteger,
        userState: UserState,
        request: BeginCreatePasswordCredentialRequest,
    ): BeginCreateCredentialResponse

    /**
     * Process the [BeginGetCredentialRequest] and invoke the [callback] with the result.
     *
     * @param beginGetPasswordOptions The request data from the OS that contains data about the requesting provider.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     * this to notify us that the result is no longer needed and we should stop handling it in order
     * to save our resources.
     * @param callback the callback object to be used to notify the response or error
     */
    suspend fun processGetCredentialRequest(
        requestCode: AtomicInteger,
        activeUserId: String,
        callingAppInfo: CallingAppInfo?,
        beginGetPasswordOptions: List<BeginGetPasswordOption>,
    ): List<CredentialEntry>?

}
