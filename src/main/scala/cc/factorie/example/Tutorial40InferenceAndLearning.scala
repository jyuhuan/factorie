package cc.factorie.example
import cc.factorie._
import cc.factorie.app.nlp._
import cc.factorie.app.chain._
import optimize.SampleRankTrainer

object Tutorial40InferenceAndLearning {
  def main(args:Array[String]): Unit = {
    /*& Here we set up a simple linear chain CRF, such as the one used for part-of-speech tagging,
     * named-entity recognition, or noun phrase chunking. It will be our running example in this
     * tutorial, but most of the things we'll discuss generalize all across factorie.
     **/
    object LabelDomain extends CategoricalDomain[String]
    class Label(val token: Token, s: String) extends LabeledCategoricalVariable(s) {
      def domain = LabelDomain
    }
    object FeaturesDomain extends CategoricalDimensionTensorDomain[String]
    class Features(val token: Token) extends BinaryFeatureVectorVariable[String] {
      def domain = FeaturesDomain
    }

    /*& The ChainModel class implements a default model for linear chains.
     * It by default implements all the factor templates one expects from a linear chain model,
     * with the exception that the (label, label, features) template is optional.
     *
     * As we saw in the Model tutorial, a model in factorie is an object that
     * can take some variables and return some factors, which know how to score
     * assignments of those variables. So models store weightsSet, and things like that.
     *
     * To construct a chain model you need to give it a few things. First, the domains
     * of the labels and the token features. Then, functions that can take a label to
     * its feature vector, a label to its token, and a token to its label. These functions
     * are used by the ChainModel class to generate factors.
     **/
    object model extends ChainModel[Label,  Features, Token](
      LabelDomain,
      FeaturesDomain,
      l => l.token.attr[Features],
      l => l.token,
      t => t.attr[Label])

    // The Document class implements documents as sequences of sentences and tokens.
    val document = new Document("The quick brown fox jumped over the lazy dog.")
    val tokenizer = new app.nlp.segment.Tokenizer
    tokenizer.process(document)
    val segmenter = new app.nlp.segment.SentenceSegmenter
    segmenter.process(document)
    assertStringEquals(document.tokens.length, "10")
    assertStringEquals(document.sentences.length, "1")

    // Let's assign all tokens the same label for the sake of simplicity
    document.tokens.foreach(t => t.attr += new Label(t, "A"))
    // Let's also have another possible Label value to make things interesting
    LabelDomain.index("B")
    // Let's also initialize features for all tokens
    document.tokens.foreach(t => {
      val features = t.attr += new Features(t)
      // One feature for the token's string value
      features += "W=" + t.string.toLowerCase
      // And one feature for its capitalization
      features += "IsCapitalized=" + t.string(0).isUpper.toString
    })

    /*&
     * Now that we have one sentence and a model we can start to do inference in it.
     *
     * Inference in factorie is abstracted in objects that implement the Infer trait,
     * which has one method, infer, that takes a sequence of variables, a model, and an
     * optional Summary, and returns a Summary object with the result of inference.
     *
     * A Summary object has two essential pieces of information in it: it allows you to
     * compute the normalization constant of the model (useful for learning), and it can
     * return marginal distributions over subsets of variables in your model. It also
     * knows how to turn these marginals into expected sufficient statistics for learning.
     *
     * The most commonly used Infer objects are those in the BP package.
     **/

    val summary = InferByBPChainSum.infer(document.tokens.map(_.attr[Label]), model).head
    assertStringEquals(summary.logZ, "6.931471805599453")
    assertStringEquals(summary.marginal(document.tokens.head.attr[Label]).proportions, "Tensor1(0.5,0.5)")

    /*&
     * Aside from InferByBPChainSum, which knows how to run forward-backward on chain models
     * factorie has MaximizeByBPChain, which runs viterbi, InferByBPTreeSum, which runs BP on
     * trees, and InferByBPLoopy which runs loopy belief propagation.
     *
     * It is easy to implement one's own Infer object, and indeed the ChainModel does so with
     * more efficient code.
     **/

    val summary1 = model.inferBySumProduct(document.tokens.map(_.attr[Label]))
    assertStringEquals(summary1.logZ, "6.931471805599453")
    assertStringEquals(summary1.marginal(document.tokens.head.attr[Label]).proportions, "Proportions(0.5,0.5)")


    /*&
     * One of the main uses of inference is in learning.
     *
     * The learning infrastructure in factorie is made of three components which can
     * be mixed and matched at will.
     *
     * An Example abstracts over a piece of data and knows how to compute objectives
     * and gradients.
     *
     * GradientOptimizers know how to update a model's weightsSet given gradients and values.
     *
     * In the middle between Examples and GradientOptimizers we have Trainers, which control
     * when the gradients are evaluated, where they are stored, the degree of parallelism, etc.
     **/



    /*& The most common example one uses is the LikelihoodExample, which computes the value and the
     * gradient for maximum likelihood training. Here's how to construct one for this sentence
     * using Factorie's BP inferencer.
     **/
    val example0 = new optimize.LikelihoodExample(model, document.tokens.map(_.attr[Label]), InferByBPChainSum)

    // The ChainModel, however, comes with its own more efficient example
    val example1 = ChainModel.createChainExample(model, document.tokens.map(_.attr[Label]))

    /*& In this tutorial let's use the AdaGrad optimizer, which is efficient and has
     * per-coordinate learning rates but is unregularized
     **/
    val optimizer0 = new optimize.AdaGrad()

    /*&
     * To learn a model we need a trainer. We can do stochastic single-threaded training with the
     * SGDTrainer. We can also do multithreaded stochastic learning with the HogwildTrainer.
     **/
    val trainer0 = new optimize.OnlineTrainer(model.weightsSet, optimizer0)
    // One call to processExamples will do one pass over the training set doing updates.
    trainer0.processExamples(Seq(example1))

    // Factorie also supports batch learning. Note that regularization is built into the optimizer
    val optimizer1 = new optimize.LBFGS with optimize.L2Regularization
    optimizer1.variance = 10000.0
    val trainer1 = new optimize.BatchTrainer(model.weightsSet, optimizer1)
    // For batch learning we can test for convergence
    while (!trainer1.isConverged) {
      trainer1.processExamples(Seq(example1))
    }

    /*&
     * Factorie also supports other batch trainers. The ParallelBatchTrainer keeps a per-thread
     * gradient vector and aggregates all gradients before sending them to the optimizer, while
     * the SynchronizedBatchTrainer keeps a single gradient vector and locks it.
     *
     * Also note that all online optimizers can be used with the batch trainers, but not the
     * other way around.
     */

    // Now we can run inference and see that we have learned
    val summary2 = model.inferBySumProduct(document.tokens.map(_.attr[Label]))
    assertStringEquals(summary2.logZ, "48.605506179720656")
    assertStringEquals(summary2.marginal(document.tokens.head.attr[Label]).proportions, "Proportions(0.9999301965748612,6.980342513888636E-5)")

    /*&
     * Factorie also has support for more efficient learning algorithms than traditional
     * inference-based batch and online methods.
     *
     * The main such method is SampleRank, which runs a sampler and updates the weightsSet
     * while the sampler explores the posterior to make the model's predictions match an
     * arbitrary loss function.
    **/
    val sampler = new GibbsSampler(model, HammingObjective)
    val sampleRankExamples = document.tokens.map(t => new optimize.SampleRankExample(t.attr[Label], sampler))
    trainer0.processExamples(sampleRankExamples)
    // SampleRank comes with its own trainer, however, for ease of use
    val trainer2 = new SampleRankTrainer(model.weightsSet, sampler, optimizer0)
    trainer2.processContexts(document.tokens.map(_.attr[Label]))

    /*&
     * Finally, there are many other useful examples in factorie. The GLMExample implements generalized
     * linear models for many classification loss functions, for example, and the
     * DominationLossExample knows how to do learning to rank.
     **/
  }
}
