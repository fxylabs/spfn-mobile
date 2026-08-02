// SPFN Mobile — what the three contract operations answer.
//
// The dataset is fixed and small on purpose. An integration test that asserts on exact
// values is evidence that the bytes crossed a socket unchanged; one that asserts "some
// items came back" would still pass against a server that answered with anything.

package xyz.superfunction.spfn.reference

import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnEchoResponse
import xyz.superfunction.spfn.generated.SpfnItem
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnListItemsResponse

/**
 * The items `items.list` pages through.
 *
 * Ordered by id, which is also what a cursor names, so paging is a pure function of the
 * request and both suites can assert the same answers.
 */
object SpfnReferenceCatalogue
{
    val ITEMS: List<SpfnItem> = listOf(
        SpfnItem(id = "item-0001", name = "alpha", updatedAtMillis = 1_750_000_000_001),
        SpfnItem(id = "item-0002", name = "bravo", updatedAtMillis = 1_750_000_000_002),
        SpfnItem(id = "item-0003", name = "charlie", updatedAtMillis = 1_750_000_000_003),
        SpfnItem(id = "item-0004", name = "delta", updatedAtMillis = 1_750_000_000_004),
        SpfnItem(id = "item-0005", name = "echo", updatedAtMillis = 1_750_000_000_005)
    )

    /** The largest page this server will answer with. */
    const val MAX_LIMIT: Long = 100
}

object SpfnReferenceOperations
{
    /** Echoes the request back with the instant the server processed it. */
    fun echo(request: SpfnEchoRequest, serverTimeMillis: Long): SpfnEchoResponse = SpfnEchoResponse(
        message = request.message,
        sequence = request.sequence,
        serverTimeMillis = serverTimeMillis
    )

    /**
     * One page of the catalogue, or the refusal explaining why the request is not one
     * this contract describes.
     *
     * A cursor names the last item of the previous page. An unknown cursor and a limit
     * outside `1 … MAX_LIMIT` are refused rather than clamped: a server that quietly
     * repaired a request would hide the client bug that produced it, and there is no
     * contract code for "the value was out of range" to hide it behind either.
     */
    fun listItems(request: SpfnListItemsRequest): Result = when
    {
        request.limit < 1 || request.limit > SpfnReferenceCatalogue.MAX_LIMIT ->
            Result.Refused(SpfnReferenceRefusal.bodyNotTheDeclaredType())

        else -> page(request)
    }

    private fun page(request: SpfnListItemsRequest): Result
    {
        val items = SpfnReferenceCatalogue.ITEMS;
        val start = if (request.cursor == null)
        {
            0
        }
        else
        {
            val index = items.indexOfFirst { it.id == request.cursor };
            if (index < 0)
            {
                return Result.Refused(SpfnReferenceRefusal.bodyNotTheDeclaredType());
            }
            index + 1;
        };

        val end = minOf(items.size.toLong(), start + request.limit).toInt();
        val page = items.subList(start, end);

        // Present only when a further page exists, so "nextCursor is absent" is a fact
        // about the data rather than a value the client has to interpret.
        val nextCursor = if (end < items.size) page.lastOrNull()?.id else null;
        return Result.Page(SpfnListItemsResponse(items = page, nextCursor = nextCursor));
    }

    sealed interface Result
    {
        class Page(val response: SpfnListItemsResponse) : Result

        class Refused(val refusal: SpfnReferenceRefusal) : Result
    }
}
